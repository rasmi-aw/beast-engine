package com.beastwall.beastengine;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class BeastEngine {
    static final String TAG_PREFIX = "bs:";
    static String TEMPLATES_PATH;
    static Pattern INTERPOLATION_PATTERN = Pattern.compile("\\{\\{\\s*(.*?)\\s*\\}\\}");

    // Cache for precompiled templates and rendered components
    final ThreadLocal<Map<String, String>> precompiledTemplates = ThreadLocal.withInitial(ConcurrentHashMap::new);
    final ThreadLocal<Map<String, String>> componentCache = ThreadLocal.withInitial(ConcurrentHashMap::new);
    static final Map<String, String> components = new ConcurrentHashMap<>();

    // Thread-local cache for resolved variables
    final ThreadLocal<Map<String, Object>> resolvedVariables = ThreadLocal.withInitial(HashMap::new);

    // JavaScript engine for expression evaluation
    final ThreadLocal<ScriptEngine> engine = ThreadLocal.withInitial(() -> {
        ScriptEngineManager manager = new ScriptEngineManager();
        return manager.getEngineByName("nashorn");
    });


    // Constructor initializing the ScriptEngine for Nashorn
    public BeastEngine() {
        TEMPLATES_PATH = "/components/";
    }

    public BeastEngine(String componentsPath) {
        TEMPLATES_PATH = componentsPath;
    }


    /**
     * Process a template string with the given context.
     *
     * @param template The template string to process.
     * @param context  The context containing variables for the template.
     * @return The processed output string.
     * @throws Exception If an error occurs during processing.
     */
    public abstract String process(String template, Map<String, Object> context) throws Exception;

    /**
     * Render a template from a resource file with the given context.
     *
     * @param componentName The name of the template resource to render.
     * @param context       The context containing variables for the template.
     * @return The rendered output string.
     * @throws Exception If an error occurs during rendering.
     */
    public abstract String processComponent(String componentName, Map<String, Object> context) throws Exception;

    /**
     *
     */
    abstract String componentExtension();

    /**
     * Read a template from a resource file.
     *
     * @param name The name of the template resource to read.
     * @return The contents of the template as a string.
     */
    String readComponent(String name) {
        String result = components.get(name + ".component" + componentExtension());
        if (result == null) {
            String path = getComponentPath() + name + "/" + name + ".component" + componentExtension();
            try (InputStream inputStream = getClass().getResourceAsStream(path);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                result = reader.lines().collect(Collectors.joining(System.lineSeparator()));
                components.put(name + ".component" + componentExtension(), result);
            } catch (Exception e) {
                throw new RuntimeException("Error reading template: " + name, e);
            }
        }
        return result;
    }

    abstract String getComponentPath();


    String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    public void clearCache() {
        resolvedVariables.remove();
        engine.remove();
    }

    String replaceVariablesWithValues(String expression, Map<String, Object> context, String scopeIdentifier) {
        Pattern variablePattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_.]*)\\b");
        Matcher matcher = variablePattern.matcher(expression);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            Object value = resolveVariableCached(variableName, context, scopeIdentifier);
            matcher.appendReplacement(result, value != null ? value.toString() : "false");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // Updated evaluateCondition method for handling nested property paths and simple boolean conditions
    boolean evaluateCondition(String expression, Map<String, Object> context, String scopeIdentifier) {
        // Check for simple boolean variable conditions
        if (expression.matches("\\b[a-zA-Z_][a-zA-Z0-9_.]*\\b")) {
            Object value = resolveNestedVariable(expression, context, scopeIdentifier);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            // Handle cases where the value might be a boolean primitive or wrapped boolean
            if (value != null) {
                return Boolean.parseBoolean(value.toString());
            }
        }

        // For more complex conditions, use the script engine
        try {
            // Bind variables from the context to the JavaScript engine
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                engine.get().put(entry.getKey(), entry.getValue());
            }

            // Evaluate the original expression with Nashorn
            Object result = engine.get().eval(expression);
            return Boolean.parseBoolean(result.toString());
        } catch (ScriptException e) {
            throw new RuntimeException("Error evaluating expression: " + expression, e);
        }
    }

    // Helper method to resolve nested variables
    Object resolveNestedVariable(String expression, Map<String, Object> context, String scopeIdentifier) {
        String[] parts = expression.split("\\.");
        Object value = resolveVariableCached(parts[0], context, scopeIdentifier);

        for (int i = 1; i < parts.length && value != null; i++) {
            try {
                String propertyName = parts[i];
                if (value instanceof Map) {
                    value = ((Map<?, ?>) value).get(propertyName);
                } else {
                    // Handle nested property access via getter methods
                    String capitalizedPropertyName = capitalize(propertyName);
                    Method method = value.getClass().getMethod("get" + capitalizedPropertyName);
                    value = method.invoke(value);
                }
            } catch (Exception e) {
                System.err.println("Error resolving nested variable: " + expression + " - " + e.getMessage());
                return null;
            }
        }
        return value;
    }

    // Updated resolveVariable method to handle nested property paths and boolean values
    Object resolveVariable(String expression, Map<String, Object> context) {
        if (!Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*$").matcher(expression).matches()) {
            // not a variable
            return null;
        }
        String[] parts = expression.split("\\.");
        Object value = context.get(parts[0]);

        for (int i = 1; i < parts.length && value != null; i++) {
            try {
                String propertyName = parts[i];
                Method method = null;
                Class<?> valueClass = value.getClass();

                if (boolean.class.equals(valueClass) || Boolean.class.equals(valueClass)) {
                    try {
                        Field field = valueClass.getField(propertyName);
                        return field.get(value);
                    } catch (NoSuchFieldException e) {
                        String capitalizedPropertyName = capitalize(propertyName);
                        try {
                            method = valueClass.getMethod("is" + capitalizedPropertyName);
                        } catch (NoSuchMethodException ex) {
                            throw new NoSuchMethodException("No method found for property: " + propertyName);
                        }
                    }
                } else {
                    String capitalizedPropertyName = capitalize(propertyName);
                    try {
                        method = valueClass.getMethod("get" + capitalizedPropertyName);
                    } catch (NoSuchMethodException e) {
                        try {
                            method = valueClass.getMethod("is" + capitalizedPropertyName);
                        } catch (NoSuchMethodException ex) {
                            throw new NoSuchMethodException("No method found for property: " + propertyName);
                        }
                    }
                }

                value = method.invoke(value);
            } catch (Exception e) {
                System.err.println("Error resolving variable: " + expression + " - " + e.getMessage());
                return null;
            }
        }
        return value;
    }


    Object resolveVariableCached(String expression, Map<String, Object> context, String scopeIdentifier) {
        Map<String, Object> cache = resolvedVariables.get();
        String cacheKey = scopeIdentifier + ":" + expression;
        return cache.computeIfAbsent(cacheKey, k -> resolveVariable(expression, context));
    }

    Object eval(String expression, Map<String, Object> context, String scopeIdentifier) throws ScriptException {
        Object resolved = resolveVariableCached(expression, context, scopeIdentifier);
        if (resolved == null) {
            resolved = engine.get().eval(expression);
        }
        return resolved;
    }

    void processText(String text, Map<String, Object> context, StringBuilder result, String scopeIdentifier) throws ScriptException {
        Matcher matcher = INTERPOLATION_PATTERN.matcher(text);
        int lastIndex = 0;

        while (matcher.find()) {
            result.append(text, lastIndex, matcher.start());
            String expression = matcher.group(1).trim();
            Object resolved = eval(expression, context, scopeIdentifier);
            result.append(resolved != null ? resolved.toString() : "");
            lastIndex = matcher.end();
        }

        result.append(text, lastIndex, text.length());
    }

}
