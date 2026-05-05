import java.lang.reflect.*;

public class Inspect3 {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("info.openrocket.core.simulation.FlightDataType");
        System.out.println("Class: " + c);
        for (Method m : c.getMethods()) {
            System.out.println(m);
        }
        for (Field f : c.getFields()) {
            System.out.println("FIELD: " + f);
        }
    }
}
