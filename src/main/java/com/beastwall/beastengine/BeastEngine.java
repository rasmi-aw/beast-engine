package com.beastwall.beastengine;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;

import javax.script.*;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * BeastEngine is an abstract base class for template processing engines.
 * It provides core functionality for template parsing, caching, and evaluation.
 * u
 *
 * @author github.com/rasmi-aw
 * @author beastwall.com
 */
public abstract class BeastEngine {
    protected static final String TAG_PREFIX = "bs:";
    protected static String TEMPLATES_PATH;
    static Pattern INTERPOLATION_PATTERN = Pattern.compile("\\{\\{\\s*(.*?)\\s*\\}\\}");

    static final Pattern SIMPLE_VARIABLE_PATTERN = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*$");

    static final ThreadLocal<ScriptEngine> scriptEngineThreadLocal = ThreadLocal.withInitial(() ->
            new ScriptEngineManager().getEngineByName("nashorn")
    );

    static final ThreadLocal<HashMap<String, CompiledScript>> expressionCacheThreadLocal = ThreadLocal.withInitial(HashMap::new);

    static final ThreadLocal<HashMap<String, Method>> methodCacheThreadLocal = ThreadLocal.withInitial(HashMap::new);

    static final ThreadLocal<StringBuilder> stringBuilderPool = ThreadLocal.withInitial(() ->
            new StringBuilder(1024));
    protected static final Map<String, Object> components = new ConcurrentHashMap<>();

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
    }


    /**
     * Process a template string with the given context.
     *
     * @param template The template string to process.
     * @param context  The context containing variables for the template.
     * @return The processed output string.
     * @throws Exception If an error occurs during processing.
     */
    public abstract String process(String template, Context context) throws Exception;

    /**
     * Render a template from a resource file with the given context.
     *
     * @param componentName The name of the template resource to render.
     * @param context       The context containing variables for the template.
     * @return The rendered output string.
     * @throws Exception If an error occurs during rendering.
     */
    public abstract String processComponent(String componentName, Context context) throws Exception;

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
    protected Object readComponent(String name) throws IOException {
        // case it's cached
        Object cmp = components.get(name + ".component" + componentExtension());
        if (cmp == null) {
            if (TEMPLATES_PATH == null || TEMPLATES_PATH.trim().isEmpty()) {
                TEMPLATES_PATH = "app";
            }
            String path = name.trim().equals("app") ? TEMPLATES_PATH + "/" + name + ".component.html" : TEMPLATES_PATH + "/" + name + "/" + name + ".component" + componentExtension();
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path);

            if (inputStream == null) {
                throw new RuntimeException("Couldn't find component: " + name + ".component" + componentExtension());
            }
            //
            cmp = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (componentExtension().equals(".html"))
                cmp = Jsoup.parse((String) cmp, "", Parser.htmlParser());

            components.put(name + ".component" + componentExtension(), cmp);
            return cmp;
        }
        return cmp;
    }

    /**
     * Read a template from a resource file.
     *
     * @param name The name of the template resource to read.
     * @return The contents of the template as a string.
     */
    protected String readStrComponent(String name) throws IOException {
        // case it's cached
        String cmp = ((String) components.get(name + ".component" + componentExtension()));
        if (cmp == null) {
            if (TEMPLATES_PATH == null || TEMPLATES_PATH.trim().isEmpty()) {
                TEMPLATES_PATH = "app";
            }
            String path = name.trim().equals("app") ? TEMPLATES_PATH + "/" + name + ".component.html" : TEMPLATES_PATH + "/" + name + "/" + name + ".component" + componentExtension();
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path);

            if (inputStream == null) {
                throw new RuntimeException("Couldn't find component: " + name + ".component" + componentExtension());
            }
            cmp = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            components.put(name + ".component" + componentExtension(), cmp);
            return cmp;
        }
        return cmp;
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
     * Evaluate a conditional expression within the given context.
     *
     * @param expression      The conditional expression to evaluate.
     * @param context         The context containing variables for evaluation.
     * @param scopeIdentifier The identifier for the current scope.
     * @return The result of the condition evaluation.
     */
    boolean evaluateCondition(String expression, Context context, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) {
        // Check for simple boolean variable conditions
        if (expression.matches("\\b[a-zA-Z_][a-zA-Z0-9_.]*\\b")) {
            Object value = resolveNestedVariable(expression, context, scopeIdentifier, resolvedVariables);
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
            // Evaluate the original expression with Nashorn
            Object result = engine.eval(expression);
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
    Object resolveNestedVariable(String expression, Context context, String scopeIdentifier, Map<String, Object> resolvedVariables) {
        String[] parts = expression.split("\\.");
        Object value = resolveVariableCached(parts[0], context, scopeIdentifier, resolvedVariables);

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
    Object resolveVariable(String expression, Context context) {
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
    Object resolveVariableCached(String expression, Context context, String scopeIdentifier, Map<String, Object> resolvedVariables) {

        if (expression.equals("true"))
            return true;
        else if (expression.equals("false"))
            return false;
        // Try to parse it into different primitive or wrapper types
        try {
            return Integer.parseInt(expression);
        } catch (NumberFormatException ignored) {
        }

        try {
            return Long.parseLong(expression);
        } catch (NumberFormatException ignored) {
        }

        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException ignored) {
        }

        try {
            return Float.parseFloat(expression);
        } catch (NumberFormatException ignored) {
        }
        String cacheKey = scopeIdentifier + ":" + expression;
        Object var = resolvedVariables.get(cacheKey);
        if (var == null) {
            var = resolveVariable(expression, context);
            resolvedVariables.put(cacheKey, var);
            return var;
        }
        return var;
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
    Object eval(String expression, Context context, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws ScriptException {
        Object resolved = resolveVariableCached(expression, context, scopeIdentifier, resolvedVariables);
        engine.put(scopeIdentifier, resolved);
        if (resolved == null) {
            resolved = engine.eval(expression);
        }
        return resolved;
    }

    /**
     * Process text by interpolating variables and expressions.
     *
     * @param content         The text to process or an element that contains the text.
     * @param context         The context containing variables for interpolation.
     * @param scopeIdentifier The identifier for the current scope.
     * @throws ScriptException If an error occurs during script evaluation.
     */
    String processText(Object content, Context context, String scopeIdentifier, Map<String, Object> resolvedVariables, ScriptEngine engine) throws ScriptException {
        String text;
        if (content instanceof Element)
            text = ((Element) content).text();
        else text = content.toString();
        Matcher matcher = INTERPOLATION_PATTERN.matcher(text);
        int lastIndex = 0;
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            result.append(text, lastIndex, matcher.start());
            String expression = matcher.group(1).trim();
            Object resolved = eval(expression, context, scopeIdentifier, resolvedVariables, engine);
            result.append(resolved != null ? resolved.toString() : "");
            lastIndex = matcher.end();
        }
        result.append(text, lastIndex, text.length());
        if (content instanceof Element)
            ((Element) content).text(result.toString());

        return result.toString();
    }

    /**
     * Clear the cache and reset the script engine.
     */
    void clearCache() {
        expressionCacheThreadLocal.get().clear();
        methodCacheThreadLocal.get().clear();
        stringBuilderPool.get().setLength(0);
        stringBuilderPool.get().setLength(1024);
        //scriptEngineThreadLocal.get().createBindings().clear();
    }

}