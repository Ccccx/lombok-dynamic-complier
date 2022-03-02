package com.example.dynamic;

import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.BaseFileObject;
import lombok.extern.slf4j.Slf4j;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;

@Slf4j
public class DynamicCompiler {

    private final JavaCompiler javaCompiler = JavacTool.create();
    private final StandardJavaFileManager standardFileManager;
    private final List<String> options = new ArrayList<>();
    private final DynamicClassLoader dynamicClassLoader;

    private final Collection<JavaFileObject> compilationUnits = new ArrayList<>();
    private final List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
    private final List<Diagnostic<? extends JavaFileObject>> warnings = new ArrayList<>();

    public DynamicCompiler(ClassLoader classLoader, String ...optionArgs) {
        if (javaCompiler == null) {
            throw new IllegalStateException(
                    "Can not load JavaCompiler from javax.tools.ToolProvider#getSystemJavaCompiler(),"
                            + " please confirm the application running in JDK not JRE.");
        }
        standardFileManager = javaCompiler.getStandardFileManager(null, null, null);
        options.add("-verbose");
        options.addAll(Arrays.asList(optionArgs));
        dynamicClassLoader = new DynamicClassLoader(classLoader);
    }

    public void addSource(String className, String source) {
        addSource(new StringJavaFileObject(className, source));
    }

    public void addSource(JavaFileObject javaFileObject) {
        compilationUnits.add(javaFileObject);
    }

    public Map<String, Class<?>> build() {

        errors.clear();
        warnings.clear();

        JavaFileManager fileManager = new DynamicJavaFileManager(standardFileManager, dynamicClassLoader, this);

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = javaCompiler.getTask(null, fileManager, collector, options, null,
                compilationUnits);

        try {
            if (compilationUnits != null) {
                boolean result = task.call();
                if (!result || collector.getDiagnostics() != null) {
                    processDiagnostic(collector);
                }
            }

            return dynamicClassLoader.getClasses();
        } catch (Throwable e) {
            throw new DynamicCompilerException(e, errors);
        } finally {
            compilationUnits.clear();
        }

    }

    private void processDiagnostic(DiagnosticCollector<JavaFileObject> collector) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            switch (diagnostic.getKind()) {
                case NOTE:
                case MANDATORY_WARNING:
                case WARNING:
                    warnings.add(diagnostic);
                    break;
                case OTHER:
                case ERROR:
                default:
                    errors.add(diagnostic);
                    break;
            }

        }
        if (warnings != null && log.isDebugEnabled()) {
            for (Diagnostic<? extends JavaFileObject> warning : warnings) {
                log.debug("{}", warning);
            }
        }
        if (!errors.isEmpty()) {
            throw new DynamicCompilerException("Compilation Error", errors);
        }
    }

    @Slf4j
    public static class DynamicClassLoader extends ClassLoader {
        public final Map<String, MemoryJavaClassObject> map = new ConcurrentHashMap<>();

        public DynamicClassLoader(ClassLoader classLoader) {
            super(classLoader);
        }

        public void registerCompiledSource(MemoryJavaClassObject byteCode) {
            map.put(byteCode.getClassName(), byteCode);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            MemoryJavaClassObject byteCode = map.get(name);
            if (Objects.isNull(byteCode)) {
                return super.findClass(name);
            }
            if (Objects.nonNull(byteCode.getClazz())) {
                return byteCode.getClazz();
            }
            final byte[] b = byteCode.getBytes();
            final Class<?> aClass = super.defineClass(name, b, 0, b.length);
            byteCode.setClazz(aClass);
            return aClass;
        }

        public Map<String, Class<?>> getClasses() throws ClassNotFoundException {
            Map<String, Class<?>> classes = new HashMap<>();
            for (MemoryJavaClassObject byteCode : map.values()) {
                classes.put(byteCode.getClassName(), findClass(byteCode.getClassName()));
            }
            return classes;
        }

    }

    public static class DynamicCompilerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        public DynamicCompilerException(String message, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            super(message);
            this.diagnostics = diagnostics;
        }

        public DynamicCompilerException(Throwable cause, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            super(cause);
            this.diagnostics = diagnostics;
        }

        private List<Map<String, Object>> getErrorList() {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            if (diagnostics != null) {
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                    Map<String, Object> message = new HashMap<String, Object>();
                    message.put("line", diagnostic.getLineNumber());
                    message.put("message", diagnostic.getMessage(Locale.US));
                    messages.add(message);
                }

            }
            return messages;
        }

        private String getErrors() {
            StringBuilder errors = new StringBuilder();

            for (Map<String, Object> message : getErrorList()) {
                for (Map.Entry<String, Object> entry : message.entrySet()) {
                    Object value = entry.getValue();
                    if (value != null && !value.toString().isEmpty()) {
                        errors.append(entry.getKey());
                        errors.append(": ");
                        errors.append(value);
                    }
                    errors.append(" , ");
                }

                errors.append("\n");
            }

            return errors.toString();

        }

        @Override
        public String getMessage() {
            return super.getMessage() + "\n" + getErrors();
        }

    }

    @Slf4j
    public static class DynamicJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private  final String[] superLocationNames = {StandardLocation.PLATFORM_CLASS_PATH.name(),
                /** JPMS StandardLocation.SYSTEM_MODULES **/
                "SYSTEM_MODULES"};
        private final PackageInternalsFinder finder;

        private final DynamicClassLoader classLoader;
        private final List<MemoryJavaClassObject> byteCodes = new ArrayList<>();
        private final DynamicCompiler compiler;
        public DynamicJavaFileManager(JavaFileManager fileManager, DynamicClassLoader classLoader, DynamicCompiler compiler) {
            super(fileManager);
            this.classLoader = classLoader;
            this.compiler = compiler;
            finder = new PackageInternalsFinder(classLoader);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) throws IOException {

            for (MemoryJavaClassObject byteCode : byteCodes) {
                if (byteCode.getClassName().equals(className)) {
                    return byteCode;
                }
            }

            if (kind.equals(Kind.SOURCE)) {
                final StringJavaFileObject javaFileObject = new StringJavaFileObject(className);
                compiler.addSource(javaFileObject);
                return javaFileObject;
            }


            MemoryJavaClassObject innerClass = new MemoryJavaClassObject(className, kind);
            byteCodes.add(innerClass);
            classLoader.registerCompiledSource(innerClass);
            return innerClass;

        }

        @Override
        public ClassLoader getClassLoader(Location location) {
            return classLoader;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof MemoryJavaFileObject) {
                return ((MemoryJavaFileObject) file).binaryName();
            } else {
                /**
                 * if it's not CustomJavaFileObject, then it's coming from standard file manager
                 * - let it handle the file
                 */
                return super.inferBinaryName(location, file);
            }
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            if (a instanceof StringJavaFileObject && b instanceof BaseFileObject) {
                return a.getName().equals(b.getName());
            }
            return super.isSameFile(a, b);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                             boolean recurse) throws IOException {
            if (location instanceof StandardLocation) {
                String locationName = ((StandardLocation) location).name();
                for (String name : superLocationNames) {
                    if (name.equals(locationName)) {
                        return super.list(location, packageName, kinds, recurse);
                    }
                }
            }

            // merge JavaFileObjects from specified ClassLoader
            if (location == StandardLocation.CLASS_PATH && kinds.contains(JavaFileObject.Kind.CLASS)) {
                return new IterableJoin<>(super.list(location, packageName, kinds, recurse),
                        finder.find(packageName));
            }

            return super.list(location, packageName, kinds, recurse);
        }

          class IterableJoin<T> implements Iterable<T> {
            private final Iterable<T> first, next;

            public IterableJoin(Iterable<T> first, Iterable<T> next) {
                this.first = first;
                this.next = next;
            }

            @Override
            public Iterator<T> iterator() {
                return new IteratorJoin<T>(first.iterator(), next.iterator());
            }
        }

          class IteratorJoin<T> implements Iterator<T> {
            private final Iterator<T> first, next;

            public IteratorJoin(Iterator<T> first, Iterator<T> next) {
                this.first = first;
                this.next = next;
            }

            @Override
            public boolean hasNext() {
                return first.hasNext() || next.hasNext();
            }

            @Override
            public T next() {
                if (first.hasNext()) {
                    return first.next();
                }
                return next.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }
        }
    }

    public static class MemoryJavaClassObject extends SimpleJavaFileObject {
        private static final char PKG_SEPARATOR = '.';
        private static final char DIR_SEPARATOR = '/';
        private static final String CLASS_FILE_SUFFIX = ".class";
        private  Class<?> clazz;
        private ByteArrayOutputStream byteArrayOutputStream;

        public MemoryJavaClassObject(String className, Kind kind) {
            super(URI.create("byte:///" + className.replace(PKG_SEPARATOR, DIR_SEPARATOR)
                    + Kind.CLASS.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            if (byteArrayOutputStream == null) {
                byteArrayOutputStream = new ByteArrayOutputStream();
            }
            return byteArrayOutputStream;
        }

        public byte[] getBytes() {
            return byteArrayOutputStream.toByteArray();
        }

        public String getClassName() {
            String className = getName();
            className = className.replace(DIR_SEPARATOR, PKG_SEPARATOR);
            className = className.substring(1, className.indexOf(CLASS_FILE_SUFFIX));
            return className;
        }

        public Class<?> getClazz() {
            return clazz;
        }

        public void setClazz(Class<?> clazz) {
            this.clazz = clazz;
        }
    }

    public static class MemoryJavaFileObject implements JavaFileObject {
        private final String binaryName;
        private final URI uri;
        private final String name;

        public MemoryJavaFileObject(String binaryName, URI uri) {
            this.uri = uri;
            this.binaryName = binaryName;
            name = uri.getPath() == null ? uri.getSchemeSpecificPart() : uri.getPath();
        }

        @Override
        public URI toUri() {
            return uri;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return uri.toURL().openStream();
        }

        @Override
        public OutputStream openOutputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Writer openWriter() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLastModified() {
            return 0;
        }

        @Override
        public boolean delete() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Kind getKind() {
            return Kind.CLASS;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            String baseName = simpleName + kind.extension;
            return kind.equals(getKind())
                    && (baseName.equals(getName())
                    || getName().endsWith("/" + baseName));
        }

        @Override
        public NestingKind getNestingKind() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Modifier getAccessLevel() {
            throw new UnsupportedOperationException();
        }

        public String binaryName() {
            return binaryName;
        }

        @Override
        public String toString() {
            return this.getClass().getName() + "[" + this.toUri() + "]";
        }
    }

    public static class PackageInternalsFinder {
        private static final String CLASS_FILE_EXTENSION = ".class";
        private final ClassLoader classLoader;

        public PackageInternalsFinder(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        public List<JavaFileObject> find(String packageName) throws IOException {
            String javaPackageName = packageName.replaceAll("\\.", "/");

            List<JavaFileObject> result = new ArrayList<>();

            Enumeration<URL> urlEnumeration = classLoader.getResources(javaPackageName);
            while (urlEnumeration.hasMoreElements()) {
                URL packageFolderURL = urlEnumeration.nextElement();
                result.addAll(listUnder(packageName, packageFolderURL));
            }

            return result;
        }

        private Collection<JavaFileObject> listUnder(String packageName, URL packageFolderURL) {
            File directory = new File(decode(packageFolderURL.getFile()));
            if (directory.isDirectory()) {
                return processDir(packageName, directory);
            } else {
                return processJar(packageFolderURL);
            }
        }

        private List<JavaFileObject> processJar(URL packageFolderURL) {
            List<JavaFileObject> result = new ArrayList<>();
            try {
                String jarUri = packageFolderURL.toExternalForm().substring(0, packageFolderURL.toExternalForm().lastIndexOf("!/"));

                JarURLConnection jarConn = (JarURLConnection) packageFolderURL.openConnection();
                String rootEntryName = jarConn.getEntryName();
                int rootEnd = rootEntryName.length() + 1;

                Enumeration<JarEntry> entryEnum = jarConn.getJarFile().entries();
                while (entryEnum.hasMoreElements()) {
                    JarEntry jarEntry = entryEnum.nextElement();
                    String name = jarEntry.getName();
                    if (name.startsWith(rootEntryName) && name.indexOf('/', rootEnd) == -1 && name.endsWith(CLASS_FILE_EXTENSION)) {
                        URI uri = URI.create(jarUri + "!/" + name);
                        String binaryName = name.replace("/", ".");
                        binaryName = binaryName.replaceAll(CLASS_FILE_EXTENSION + "$", "");

                        result.add(new MemoryJavaFileObject(binaryName, uri));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Wasn't able to open " + packageFolderURL + " as a jar file", e);
            }
            return result;
        }

        private List<JavaFileObject> processDir(String packageName, File directory) {
            List<JavaFileObject> result = new ArrayList<JavaFileObject>();

            File[] childFiles = directory.listFiles();
            for (File childFile : childFiles) {
                if (childFile.isFile()) {
                    // We only want the .class files.
                    if (childFile.getName().endsWith(CLASS_FILE_EXTENSION)) {
                        String binaryName = packageName + "." + childFile.getName();
                        binaryName = binaryName.replaceAll(CLASS_FILE_EXTENSION + "$", "");

                        result.add(new MemoryJavaFileObject(binaryName, childFile.toURI()));
                    }
                }
            }

            return result;
        }

        private String decode(String filePath) {
            try {
                return URLDecoder.decode(filePath, "utf-8");
            } catch (Exception e) {
                // ignore, return original string
            }

            return filePath;
        }
    }

    public static class StringJavaFileObject extends SimpleJavaFileObject {
        private String contents;

        private ByteArrayOutputStream byteArrayOutputStream;

        public StringJavaFileObject(String className, String contents) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.contents = contents;
        }

        public StringJavaFileObject(String className) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            if (byteArrayOutputStream == null) {
                byteArrayOutputStream = new ByteArrayOutputStream();
            }
            return byteArrayOutputStream;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            if (contents != null) {
                return contents;
            }
            contents = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            return contents;
        }
    }
}
