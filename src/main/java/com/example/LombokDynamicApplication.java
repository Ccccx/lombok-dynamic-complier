package com.example;

import com.example.dynamic.DynamicCompiler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@Slf4j
@SpringBootApplication
public class LombokDynamicApplication {

    public static void main(String[] args) throws Exception {
        runTest();
    }

    private static void runTest() throws Exception {
        String name = "com.example.model.Users";
        final DynamicCompiler dynamicCompiler = new DynamicCompiler(
                LombokDynamicApplication.class.getClassLoader());
        dynamicCompiler.addSource(name, javaEntityStr());
        final Map<String, Class<?>> build = dynamicCompiler.build();
        final Class<?> aClass = build.get(name);
        final Object o = aClass.newInstance();
        StringBuilder sb = new StringBuilder("\n");
        final String split = "-------------------------------------------\n";
        sb.append(split);
        sb.append(o).append("\n");
        sb.append(split);
        log.info("{}", sb.toString());
    }

    private static String javaEntityStr() {
        return "package com.example.model;\n" +
                "\n" +
                "import lombok.Data;\n" +
                "import lombok.EqualsAndHashCode;\n" +
                "\n" +
                "import java.io.Serializable;\n" +
                "import java.lang.reflect.Method;\n" +
                " \n" +
                "@Data\n" +
                "@EqualsAndHashCode(callSuper = false)\n" +
                "public class Users implements Serializable {\n" +
                "\n" +
                "    private static final long serialVersionUID = 1L;\n" +
                "\n" +
                "    private String username;\n" +
                "\n" +
                "    private String password;\n" +
                "\n" +
                "    private String nickname;\n" +
                "\n" +
                "    @Override\n" +
                "    public String toString() {\n" +
                "        final Method[] declaredMethods = getClass().getDeclaredMethods();\n" +
                "        final String className = getClass().getSimpleName();\n" +
                "        StringBuilder sb = new StringBuilder(\"\\n\" + className + \" Methods: \");\n" +
                "        for (Method declaredMethod : declaredMethods) {\n" +
                "            sb.append(String.format(\"%s > %s \", \"\\n\\t\", declaredMethod.getName()));\n" +
                "        }\n" +
                "        return sb.toString();\n" +
                "    }\n" +
                "}\n";
    }

}
