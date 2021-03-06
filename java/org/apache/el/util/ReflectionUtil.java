/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.el.util;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.el.ELException;
import javax.el.MethodNotFoundException;
import javax.el.PropertyNotFoundException;

import org.apache.el.lang.ELSupport;


/**
 * Utilities for Managing Serialization and Reflection
 * 
 * @author Jacob Hookom [jacob@hookom.net]
 * @version $Id: ReflectionUtil.java 981816 2010-08-03 10:44:58Z markt $
 */
public class ReflectionUtil {

    protected static final String[] EMPTY_STRING = new String[0];

    protected static final String[] PRIMITIVE_NAMES = new String[] { "boolean",
            "byte", "char", "double", "float", "int", "long", "short", "void" };

    protected static final Class<?>[] PRIMITIVES = new Class[] { boolean.class,
            byte.class, char.class, double.class, float.class, int.class,
            long.class, short.class, Void.TYPE };

    private ReflectionUtil() {
        super();
    }

    public static Class<?> forName(String name) throws ClassNotFoundException {
        if (null == name || "".equals(name)) {
            return null;
        }
        Class<?> c = forNamePrimitive(name);
        if (c == null) {
            if (name.endsWith("[]")) {
                String nc = name.substring(0, name.length() - 2);
                c = Class.forName(nc, true, Thread.currentThread().getContextClassLoader());
                c = Array.newInstance(c, 0).getClass();
            } else {
                c = Class.forName(name, true, Thread.currentThread().getContextClassLoader());
            }
        }
        return c;
    }

    protected static Class<?> forNamePrimitive(String name) {
        if (name.length() <= 8) {
            int p = Arrays.binarySearch(PRIMITIVE_NAMES, name);
            if (p >= 0) {
                return PRIMITIVES[p];
            }
        }
        return null;
    }

    /**
     * Converts an array of Class names to Class types
     * @param s
     * @throws ClassNotFoundException
     */
    public static Class<?>[] toTypeArray(String[] s) throws ClassNotFoundException {
        if (s == null)
            return null;
        Class<?>[] c = new Class[s.length];
        for (int i = 0; i < s.length; i++) {
            c[i] = forName(s[i]);
        }
        return c;
    }

    /**
     * Converts an array of Class types to Class names
     * @param c
     */
    public static String[] toTypeNameArray(Class<?>[] c) {
        if (c == null)
            return null;
        String[] s = new String[c.length];
        for (int i = 0; i < c.length; i++) {
            s[i] = c[i].getName();
        }
        return s;
    }

    /**
     * Returns a method based on the criteria
     * @param base the object that owns the method
     * @param property the name of the method
     * @param paramTypes the parameter types to use
     * @return the method specified
     * @throws MethodNotFoundException
     */
    @SuppressWarnings("null")
    public static Method getMethod(Object base, Object property,
            Class<?>[] paramTypes) throws MethodNotFoundException {
        if (base == null || property == null) {
            throw new MethodNotFoundException(MessageFactory.get(
                    "error.method.notfound", base, property,
                    paramString(paramTypes)));
        }

        String methodName = (property instanceof String) ? (String) property
                : property.toString();
        
        int paramCount;
        if (paramTypes == null) {
            paramCount = 0;
        } else {
            paramCount = paramTypes.length;
        }

        Method[] methods = base.getClass().getMethods();
        Map<Method,Integer> candidates = new HashMap<Method,Integer>();
        
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) {
                // Method name doesn't match
                continue;
            }
            
            Class<?>[] mParamTypes = m.getParameterTypes();
            int mParamCount;
            if (mParamTypes == null) {
                mParamCount = 0;
            } else {
                mParamCount = mParamTypes.length;
            }
            
            // Check the number of parameters
            if (!(paramCount == mParamCount ||
                    (m.isVarArgs() && paramCount >= mParamCount))) {
                // Method has wrong number of parameters
                continue;
            }
            
            // Check the parameters match
            int exactMatch = 0;
            boolean noMatch = false;
            for (int i = 0; i < mParamCount; i++) {
                // Can't be null
                if (mParamTypes[i].equals(paramTypes[i])) {
                    exactMatch++;
                } else if (i == (mParamCount - 1) && m.isVarArgs()) {
                    Class<?> varType = mParamTypes[i].getComponentType();
                    for (int j = i; j < paramCount; j++) {
                        if (!varType.isAssignableFrom(paramTypes[j])) {
                            break;
                        }
                        // Don't treat a varArgs match as an exact match, it can
                        // lead to a varArgs method matching when the result
                        // should be ambiguous
                    }
                } else if (!mParamTypes[i].isAssignableFrom(paramTypes[i])) {
                    noMatch = true;
                    break;
                }
            }
            if (noMatch) {
                continue;
            }
            
            // If a method is found where every parameter matches exactly,
            // return it
            if (exactMatch == paramCount) {
                return m;
            }
            
            candidates.put(m, Integer.valueOf(exactMatch));
        }

        // Look for the method that has the highest number of parameters where
        // the type matches exactly
        int bestMatch = 0;
        Method match = null;
        boolean multiple = false;
        for (Map.Entry<Method, Integer> entry : candidates.entrySet()) {
            if (entry.getValue().intValue() > bestMatch ||
                    match == null) {
                bestMatch = entry.getValue().intValue();
                match = entry.getKey();
                multiple = false;
            } else if (entry.getValue().intValue() == bestMatch) {
                multiple = true;
            }
        }
        if (multiple) {
            if (bestMatch == paramCount - 1) {
                // Only one parameter is not an exact match - try using the
                // super class
                match = resolveAmbiguousMethod(candidates.keySet(), paramTypes);
            } else {
                match = null;
            }
            
            if (match == null) {
                // If multiple methods have the same matching number of parameters
                // the match is ambiguous so throw an exception
                throw new MethodNotFoundException(MessageFactory.get(
                        "error.method.ambiguous", base, property,
                        paramString(paramTypes)));
                }
        }
        
        // Handle case where no match at all was found
        if (match == null) {
            throw new MethodNotFoundException(MessageFactory.get(
                        "error.method.notfound", base, property,
                        paramString(paramTypes)));
        }
        
        return match;
    }

    @SuppressWarnings("null")
    private static Method resolveAmbiguousMethod(Set<Method> candidates,
            Class<?>[] paramTypes) {
        // Identify which parameter isn't an exact match
        Method m = candidates.iterator().next();
        
        int nonMatchIndex = 0;
        Class<?> nonMatchClass = null;
        
        for (int i = 0; i < paramTypes.length; i++) {
            if (m.getParameterTypes()[i] != paramTypes[i]) {
                nonMatchIndex = i;
                nonMatchClass = paramTypes[i];
                break;
            }
        }
        
        for (Method c : candidates) {
           if (c.getParameterTypes()[nonMatchIndex] ==
                   paramTypes[nonMatchIndex]) {
               // Methods have different non-matching parameters
               // Result is ambiguous
               return null;
           }
        }
        
        // Can't be null
        nonMatchClass = nonMatchClass.getSuperclass();
        while (nonMatchClass != null) {
            for (Method c : candidates) {
                if (c.getParameterTypes()[nonMatchIndex].equals(
                        nonMatchClass)) {
                    // Found a match
                    return c;
                }
            }
            nonMatchClass = nonMatchClass.getSuperclass();
        }
        
        return null;
    }

    protected static final String paramString(Class<?>[] types) {
        if (types != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < types.length; i++) {
                sb.append(types[i].getName()).append(", ");
            }
            if (sb.length() > 2) {
                sb.setLength(sb.length() - 2);
            }
            return sb.toString();
        }
        return null;
    }

    public static PropertyDescriptor getPropertyDescriptor(Object base,
            Object property) throws ELException, PropertyNotFoundException {
        String name = ELSupport.coerceToString(property);
        try {
            PropertyDescriptor[] desc = Introspector.getBeanInfo(
                    base.getClass()).getPropertyDescriptors();
            for (int i = 0; i < desc.length; i++) {
                if (desc[i].getName().equals(name)) {
                    return desc[i];
                }
            }
        } catch (IntrospectionException ie) {
            throw new ELException(ie);
        }
        throw new PropertyNotFoundException(MessageFactory.get(
                "error.property.notfound", base, name));
    }
}
