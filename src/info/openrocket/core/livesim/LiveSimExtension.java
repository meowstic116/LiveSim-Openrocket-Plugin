package info.openrocket.core.livesim;

import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;

/**
 * LiveSimExtension is the "glue" class between the Provider (which
 * registers the plugin with OR's menu) and the Listener (which does
 * the actual work during simulation).
 *
 * AbstractSimulationExtension gives us a config store and a hook
 * to attach our listener before the simulation starts.
 */
public class LiveSimExtension extends AbstractSimulationExtension {

    /**
     * getName() is what appears in the "Add Extension" menu inside
     * OpenRocket's simulation options. Keep it short and descriptive.
     */
    @Override
    public String getName() {
        return "Live Flight Viewer";
    }

    /**
     * getDescription() is shown as a tooltip or subtitle in the UI.
     */
    @Override
    public String getDescription() {
        return "Plays back the simulated flight as an animated graph after the simulation completes.";
    }

    /**
     * initialize() is called by OpenRocket just before the simulation runs.
     * This is where we attach our listener to the simulation conditions so
     * OR knows to call our listener's callbacks during the run.
     *
     * @param conditions  the simulation setup object we attach our listener to
     */
    @Override
    public void initialize(SimulationConditions conditions) throws SimulationException {
        // addSimulationListener() registers our LiveSimListener with OR's
        // simulation engine. OR will then call our listener's methods
        // (e.g. endSimulation) at the appropriate times.
        conditions.getSimulationListenerList().add(new LiveSimListener());
    }
}
