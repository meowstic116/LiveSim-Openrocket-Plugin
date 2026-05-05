package info.openrocket.core.livesim;

import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.List;

/**
 * LiveSimListener hooks into OpenRocket's simulation engine.
 * 
 * AbstractSimulationListener is OR's base class that gives us
 * callbacks at key moments during a simulation run. We only
 * need to override the methods we care about — the rest are
 * no-ops by default.
 */
public class LiveSimListener extends AbstractSimulationListener {

    /**
     * endSimulation() is called once by OpenRocket after the simulation
     * has fully completed. At this point, all flight data is available
     * in the SimulationStatus object.
     *
     * param status  contains all simulation state and recorded data
     * param exception   the exception that caused ending the simulation, or null if ending normally
     */
    @Override
    public void endSimulation(SimulationStatus status, SimulationException exception) {

        System.out.println("LiveSimListener.endSimulation called, exception: " + exception);

        // If the simulation failed, don't show the window
        if (exception != null) {
            System.out.println("Simulation failed, not showing window");
            return;
        }

        // Get the flight data branch from the status
        FlightDataBranch data = status.getFlightDataBranch();

        if (data == null) {
            System.out.println("FlightDataBranch is null, not showing window");
            return;
        }

        System.out.println("FlightDataBranch obtained, proceeding to show window");

        // FlightDataType constants are OR's way of labeling each data channel.
        // We pull out the raw lists of doubles for each channel we want to display.
        List<Double> time         = data.get(FlightDataType.TYPE_TIME);
        List<Double> altitude     = data.get(FlightDataType.TYPE_ALTITUDE);
        List<Double> velocity     = data.get(FlightDataType.TYPE_VELOCITY_TOTAL);
        List<Double> acceleration = data.get(FlightDataType.TYPE_ACCELERATION_TOTAL);

        System.out.println("Data lists: time=" + time.size() + ", alt=" + altitude.size() + ", vel=" + velocity.size() + ", acc=" + acceleration.size());

        // Get flight events (e.g., Motor Burnout, Apogee, etc.)
        List<FlightEvent> flightEvents = data.getEvents();
        System.out.println("Flight events count: " + (flightEvents != null ? flightEvents.size() : 0));

        // All Swing UI work must happen on the Event Dispatch Thread (EDT).
        // SwingUtilities.invokeLater() schedules our window creation to run
        // on the EDT once the current simulation thread finishes.
        SwingUtilities.invokeLater(() -> {
            System.out.println("Creating LiveSimWindow on EDT");
            LiveSimWindow window = new LiveSimWindow(data, flightEvents);
            window.setAlwaysOnTop(true);
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
            window.startPlayback();

            // Keep the window on top briefly so it isn't immediately pushed behind OpenRocket.
            // After a short delay, restore normal z-order behavior.
            new Timer(200, e -> {
                window.setAlwaysOnTop(false);
                ((Timer) e.getSource()).stop();
            }).start();

            System.out.println("Window should be visible now");
        });
    }
}
