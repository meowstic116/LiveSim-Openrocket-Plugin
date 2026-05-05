package info.openrocket.core.livesim;

import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.extension.AbstractSimulationExtensionProvider;

/**
 * LiveSimProvider is what OpenRocket actually discovers when it scans
 * the Plugins directory. It acts as a factory — OR asks it for an
 * instance of the extension when the user selects it from the menu.
 *
 * The @Plugin annotation is required. It tells OR's plugin scanner
 * that this class should be loaded as a plugin entry point.
 */
@Plugin
public class LiveSimProvider extends AbstractSimulationExtensionProvider {

    /**
     * The super() call registers:
     *   1. The extension class this provider creates instances of
     *   2. The menu path where it appears in "Add Extension"
     *      (first arg = category, second = display name)
     *
     * So this will show up under: Flight > Live Flight Viewer
     */
    public LiveSimProvider() {
        super(LiveSimExtension.class, "Flight", "Live Flight Viewer");
    }
}
