package com.zcx.chenstack.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TagNames {

    public static final String SPRING_BOOT = "spring boot";
    private static final Pattern SPRING_BOOT_ALIAS = Pattern.compile("spring\\s*boot", Pattern.CASE_INSENSITIVE);

    private TagNames() {
    }

    public static String normalize(String name) {
        return name != null && SPRING_BOOT_ALIAS.matcher(name.strip()).matches() ? SPRING_BOOT : name;
    }

    public static String normalizeArticleTags(String tags) {
        if (tags == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        boolean springBootAdded = false;
        for (String name : tags.split(",", -1)) {
            if (SPRING_BOOT.equals(normalize(name))) {
                if (!springBootAdded) {
                    names.add(SPRING_BOOT);
                    springBootAdded = true;
                }
            } else {
                names.add(name);
            }
        }
        return String.join(",", names);
    }
}
