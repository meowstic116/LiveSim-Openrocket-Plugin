import java.lang.reflect.*; public class Inspect2 { public static void main(String[] args) throws Exception { Class<?> c = Class.forName("info.openrocket.core.simulation.DataType"); System.out.println("Class: " + c); for (Method m: c.getMethods()) System.out.println(m); for (Field f: c.getFields()) System.out.println("FIELD: " + f); System.out.println("Enum values:"); Method valuesMethod = c.getMethod("values"); Object[] vals = (Object[]) valuesMethod.invoke(null); for (Object v : vals) {
    System.out.println("  " + v + " -> " + v.getClass().getMethod("getName").invoke(v));
}
 } }
