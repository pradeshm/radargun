package org.radargun.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Element;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

/**
 * Contains various configuration helper methods needed by different parsers.
 *
 * @author Mircea.Markus@jboss.com
 */
public class ConfigHelper {

   private static Log log = LogFactory.getLog(ConfigHelper.class);

   /**
    * Retrieves a setter name based on a field name passed in
    *
    * @param fieldName field name to find setter for
    * @return name of setter method
    */
   public static String setterName(String fieldName) {
      StringBuilder sb = new StringBuilder("set");
      if (fieldName != null && fieldName.length() > 0) {
         sb.append(fieldName.substring(0, 1).toUpperCase(Locale.ENGLISH));
         if (fieldName.length() > 1) {
            sb.append(fieldName.substring(1));
         }
      }
      return sb.toString();
   }

   public static void setValues(Object target, Map<?, ?> attribs, boolean failOnMissingSetter) {
      Class objectClass = target.getClass();

      // go thru simple string setters first.
      for (Map.Entry entry : attribs.entrySet()) {
         String propName = (String) entry.getKey();
         String setter = setterName(propName);
         Method method;

         try {
            method = objectClass.getMethod(setter, String.class);
            method.invoke(target, entry.getValue());
            continue;
         }
         catch (NoSuchMethodException me) {
            // try other setters that may fit later on.  Don't throw this exception though.
         }
         catch (Exception e) {
            throw new RuntimeException("Unable to invoke setter " + setter + " on " + objectClass, e);
         }

         boolean setterFound = false;
         // if we get here, we could not find a String or Element setter.
         for (Method m : objectClass.getMethods()) {
            if (setter.equals(m.getName())) {
               Class paramTypes[] = m.getParameterTypes();
               if (paramTypes.length != 1) {
                  if (log.isTraceEnabled()) {
                     log.trace("Rejecting setter " + m + " on class " + objectClass + " due to incorrect number of parameters");
                  }
                  continue; // try another param with the same name.
               }

               Class parameterType = paramTypes[0];
               PropertyEditor editor = PropertyEditorManager.findEditor(parameterType);
               if (editor == null) {
                  throw new RuntimeException("Couldn't find a property editor for parameter type " + parameterType);
               }

               editor.setAsText((String) attribs.get(propName));

               Object parameter = editor.getValue();
               //if (log.isDebugEnabled()) log.debug("Invoking setter method: " + setter + " with parameter \"" + parameter + "\" of type " + parameter.getClass());

               try {
                  m.invoke(target, parameter);
                  setterFound = true;
                  break;
               }
               catch (Exception e) {
                  throw new RuntimeException("Unable to invoke setter " + setter + " on " + objectClass, e);
               }
            }
         }
         if (!setterFound && failOnMissingSetter) {
            throw new RuntimeException("Couldn't find a setter named [" + setter + "] which takes a single parameter, for parameter " + propName + " on class [" + objectClass + "]");
         }
      }
   }

   //looks for this syntax: ${defaultValue:existingPropValue}
   //this is also supporrted: ${existingPropValue}
   public static String checkForProps(String val) {
      if (val == null) return val;
      val = val.trim();
      if (val.length() <= "${}".length())
         return val;
      String originalVal = val;
      if (val.startsWith("${")) {
         int end = val.indexOf('}');
         if (end < 0) throw new IllegalArgumentException(val);
         //get rid of '${' and '}'
         val = val.substring(2, end);
         int separator = val.indexOf(':');
         if (separator > 0) {
            String defaultValue = val.substring(0, separator);
            String sysProperty = val.substring(separator + 1);
            String inEnv = System.getProperties().getProperty(sysProperty);
            if (inEnv != null) {
               return inEnv;
            } else {
               return defaultValue;
            }
         } else {
            String sysProp = System.getProperties().getProperty(val);
            if (sysProp == null) {
               String errorMessage = "For property '" + originalVal + "' there's no System.property with key " + val
                     + " .Existing properties are: " + System.getProperties();
               log.error(errorMessage);
               throw new RuntimeException(errorMessage);
            } else {
               return sysProp;
            }
         }
      } else if (val.startsWith("#{") && val.endsWith("}")) {
         Stack<String> stack = new Stack<String>();
         return eval(stack, val.substring(2, val.length() - 1).trim());
      } else {
         return val;
      }
   }

   private static String eval(Stack<String> stack, String expression) {
      while (true) {
         int next;
         if (expression.startsWith("+")) {
            stack.push(plus(stack.pop(), stack.pop()));
            next = 1;
         } else if (expression.startsWith("-")) {
            stack.push(minus(stack.pop(), stack.pop()));
            next = 1;
         } else if (expression.startsWith("*")) {
            stack.push(times(stack.pop(), stack.pop()));
            next = 1;
         } else if (expression.startsWith("..")) {
            stack.push(range(stack.pop(), stack.pop()));
            next = 2;
         } else if (expression.startsWith(",")) {
            stack.push(add(stack.pop(), stack.pop()));
            next = 1;
         } else if (expression.startsWith("++")) {
            stack.push(concat(stack.pop(), stack.pop()));
            next = 1;
         } else {
            next = 0;
            int inBraces = 0;
            while (next < expression.length()) {
               char c = expression.charAt(next);
               if (Character.isWhitespace(c) && inBraces == 0) break;
               else if (c == '{') inBraces++;
               else if (c == '}') {
                  if (inBraces <= 0) throw new IllegalArgumentException("Invalid braces nesting");
                  inBraces--;
               }
               next++;
            }
            stack.push(checkForProps(expression.substring(0, next)));
         }
         while (next < expression.length() && Character.isWhitespace(expression.charAt(next))) ++next;
         if (next == expression.length()) break;
         expression = expression.substring(next);
      }
      if (stack.size() != 1) {
         StringBuilder sb = new StringBuilder("Stack is" + (stack.empty() ? " empty" : ":"));
         while (!stack.empty()) {
            sb.append("\n\t").append(stack.pop());
         }
         throw new IllegalStateException(sb.toString());
      }
      return stack.pop();
   }

   private static String add(String second, String first) {
      return first + "," + second;
   }

   private static String concat(String second, String first) {
      return first + second;
   }

   private static String range(String second, String first) {
      try {
         int from = Integer.parseInt(first);
         int to = Integer.parseInt(second);
         if (from > to) {
            int temp = from;
            from = to;
            to = temp;
         } else if (from == to) return String.valueOf(from);
         StringBuilder sb = new StringBuilder(String.valueOf(from));
         for (int i = from + 1; i <= to; ++i) sb.append(',').append(i);
         return sb.toString();
      }  catch (NumberFormatException e2) {
         throw new IllegalArgumentException(first + " .. " + second);
      }
   }

   private static String times(String second, String first) {
      try {
         return String.valueOf(Integer.parseInt(first) * Integer.parseInt(second));
      } catch (NumberFormatException e) {
         try {
            return String.valueOf(Double.parseDouble(first) * Double.parseDouble(second));
         } catch (NumberFormatException e2) {
            throw new IllegalArgumentException(first + " * " + second);
         }
      }
   }

   private static String minus(String second, String first) {
      try {
         return String.valueOf(Integer.parseInt(first) - Integer.parseInt(second));
      } catch (NumberFormatException e) {
         try {
            return String.valueOf(Double.parseDouble(first) - Double.parseDouble(second));
         } catch (NumberFormatException e2) {
            throw new IllegalArgumentException(first + " - " + second);
         }
      }
   }

   private static String plus(String second, String first) {
      try {
         return String.valueOf(Integer.parseInt(first) + Integer.parseInt(second));
      } catch (NumberFormatException e) {
         try {
            return String.valueOf(Double.parseDouble(first) + Double.parseDouble(second));
         } catch (NumberFormatException e2) {
            throw new IllegalArgumentException(first + " + " + second);
         }
      }
   }

   public static int parseInt(String val) {
      val = checkForProps(val);
      return Integer.valueOf(val);
   }

   public static float parseFloat(String val) {
      val = checkForProps(val);
      return Float.valueOf(val);
   }

   public static String parseString(String value) {
      return checkForProps(value);
   }

   public static boolean parseBoolean(String value) {
      return Boolean.valueOf(checkForProps(value));
   }

   public static String getStrAttribute(Element master, String attrName) {
      String s = master.getAttribute(attrName);
      return parseString(s);
   }

   public static int getIntAttribute(Element master, String attrName) {
      String s = master.getAttribute(attrName);
      return Integer.parseInt(parseString(s));
   }
}
