package com.beastwall.beastengine;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.HashMap;
import java.util.Map;

public class BeastTextEngine extends BeastEngine {


    public BeastTextEngine() {
        super();
    }

    public BeastTextEngine(String componentsPath) {
        super(componentsPath);
    }

    @Override
    public String process(String template, Context context) throws Exception {
        //
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        context.keySet().forEach(k -> {
            engine.put(k, context.get(k));
        });
        //
        StringBuilder builder = new StringBuilder();
        processText(template, context, null, new HashMap<>(), engine);
        String output = builder.toString();

        return output;
    }

    @Override
    public String processComponent(String componentName, Context context) throws Exception {
        return process(((String) readComponent(componentName)), context);
    }

    @Override
    String componentExtension() {
        return ".txt";
    }


}
