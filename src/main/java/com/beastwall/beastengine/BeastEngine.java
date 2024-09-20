package com.beastwall.beastengine;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * BeastEngine is an abstract base class for template processing engines.
 * It provides core functionality for template parsing, caching, and evaluation.
 *
 * @author github.com/rasmi-aw
 * @author beastwall.com
 */
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

    /**
     * Default constructor. Initializes the TEMPLATES_PATH.
     */
    public BeastEngine() {
        this("components");
    }

    /**
     * Constructor with custom components path.
     *
     * @param componentsResourceFolderName The path to the components directory.
     */
    public BeastEngine(String componentsResourceFolderName) {
        TEMPLATES_PATH = componentsResourceFolderName;
        getComponents(TEMPLATES_PATH);
    }

    /**
     * get all components in app
     */
    void getComponents(String componentsFolderName) {
        try {
            // Get the resource as a stream
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(componentsFolderName)) {
                if (inputStream == null) {
                    throw new IllegalArgumentException("Resource folder not found: " + componentsFolderName);
                }

                // Use a buffer to read the component files from the input stream
                Files.walk(Paths.get(getClass().getClassLoader().getResource(componentsFolderName).toURI()))
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                System.out.println(path.getFileName().toString());
                                String content = Files.readString(path);
                                components.put(path.getFileName().toString(), content);
                            } catch (IOException e) {
                                System.err.println("Error reading component file: " + path + " - " + e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            System.err.println("Error getting components: " + e.getMessage());
        }
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
     * Get the file extension for component files.
     *
     * @return The file extension string.
     */
    abstract String componentExtension();

    /**
     * Read a template from a resource file.
     *
     * @param name The name of the template resource to read.
     * @return The contents of the template as a string.
     */
    String readComponent(String name) {
        return components.get(name + ".component" + componentExtension());
    }

    /**
     * Capitalize the first letter of a string.
     *
     * @param s The string to capitalize.
     * @return The capitalized string.
     */
    String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /**
     * Clear the cache and reset the script engine.
     */
    public void clearCache() {
        resolvedVariables.remove();
        engine.remove();
    }

    /**
     * Replace variables in an expression with their values from the context.
     *
     * @param expression      The expression containing variables.
     * @param context         The context containing variable values.
     * @param scopeIdentifier The identifier for the current scope.
     * @return The expression with variables replaced by their values.
     */
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

    /**
     * Evaluate a conditional expression within the given context.
     *
     * @param expression      The conditional expression to evaluate.
     * @param context         The context containing variables for evaluation.
     * @param scopeIdentifier The identifier for the current scope.
     * @return The result of the condition evaluation.
     */
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

    /**
     * Resolve a nested variable from the context.
     *
     * @param expression      The variable expression to resolve.
     * @param context         The context containing variable values.
     * @param scopeIdentifier The identifier for the current scope.
     * @return The resolved value of the variable.
     */
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

    /**
     * Resolve a variable from the context.
     *
     * @param expression The variable expression to resolve.
     * @param context    The context containing variable values.
     * @return The resolved value of the variable.
     */
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

    /**
     * Resolve a variable from the context and cache the result.
     *
     * @param expression      The variable expression to resolve.
     * @param context         The context containing variable values.
     * @param scopeIdentifier The identifier for the current scope.
     * @return The resolved and cached value of the variable.
     */
    Object resolveVariableCached(String expression, Map<String, Object> context, String scopeIdentifier) {
        Map<String, Object> cache = resolvedVariables.get();
        String cacheKey = scopeIdentifier + ":" + expression;
        return cache.computeIfAbsent(cacheKey, k -> resolveVariable(expression, context));
    }

    /**
     * Evaluate an expression within the given context.
     *
     * @param expression      The expression to evaluate.
     * @param context         The context containing variables for evaluation.
     * @param scopeIdentifier The identifier for the current scope.
     * @return The result of the expression evaluation.
     * @throws ScriptException If an error occurs during script evaluation.
     */
    Object eval(String expression, Map<String, Object> context, String scopeIdentifier) throws ScriptException {
        Object resolved = resolveVariableCached(expression, context, scopeIdentifier);
        if (resolved == null) {
            resolved = engine.get().eval(expression);
        }
        return resolved;
    }

    /**
     * Process text by interpolating variables and expressions.
     *
     * @param text            The text to process.
     * @param context         The context containing variables for interpolation.
     * @param result          The StringBuilder to append the processed text to.
     * @param scopeIdentifier The identifier for the current scope.
     * @throws ScriptException If an error occurs during script evaluation.
     */
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