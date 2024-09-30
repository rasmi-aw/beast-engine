package com.beastwall.beastengine;

import java.util.HashMap;
import java.util.Locale;

public class Context extends HashMap<String, Object> {

    private Locale locale;


    public Context() {
        super();
    }

    public Context(Locale locale) {
        super();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        this.locale = locale;
    }

    public Context(Context context) {
        this(context.locale);
        this.putAll(context);
    }

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }
}
