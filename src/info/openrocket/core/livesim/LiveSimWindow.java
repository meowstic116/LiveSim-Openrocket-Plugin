package info.openrocket.core.livesim;

import info.openrocket.core.simulation.DataType;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.unit.UnitGroup;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.awt.BasicStroke;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * LiveSimWindow is the Swing window that displays the animated playback.
 *
 * It uses JFreeChart (already bundled with OpenRocket) to render three
 * live-updating line charts: altitude, velocity, and acceleration.
 *
 * The animation works by using a javax.swing.Timer to advance playback
 * by simulation time on each tick. JFreeChart automatically redraws
 * whenever the dataset changes.
 */
public class LiveSimWindow extends JFrame {

    // ── Raw data from the simulation ──────────────────────────────────────────
    private final FlightDataBranch dataBranch;
    private final List<Double> time;
    private final List<FlightEvent> flightEvents;
    private final Map<FlightDataType, List<Double>> dataByType = new HashMap<>();
    private final List<FlightDataType> availableTypes = new ArrayList<>();
    private final FlightDataType[] selectedTypes = new FlightDataType[3];

    // ── JFreeChart XYSeries objects ───────────────────────────────────────────
    // An XYSeries holds the (x, y) pairs that JFreeChart plots.
    // We add points to these incrementally to create the animation.
    private final XYSeries altSeries  = new XYSeries("Altitude");
    private final XYSeries velSeries  = new XYSeries("Velocity");
    private final XYSeries accSeries  = new XYSeries("Acceleration");
    private final XYSeries[] chartSeries = {altSeries, velSeries, accSeries};
    private final ChartPanel[] chartPanels = new ChartPanel[3];

    // ── Playback state ────────────────────────────────────────────────────────
    // Tracks which data point we're currently revealing
    private int currentIndex = 0;

    // Playback time in simulated seconds; advances independently of point count.
    private double playbackTime = 0.0;

    // Playback speed multiplier controlled by the speed slider.
    private double playbackSpeed = 1.0;

    // ── UI controls ───────────────────────────────────────────────────────────
    private Timer playbackTimer;
    private JButton playPauseButton;
    private JSlider speedSlider;
    private JLabel timeLabel;

    // ── Event marker tracking ─────────────────────────────────────────────────
    private Map<String, Boolean> eventVisibility = new HashMap<>();
    private Map<String, List<ValueMarker>> eventMarkers = new HashMap<>();
    private Map<String, Color> eventColors = new HashMap<>();
    private static final Color[] EVENT_PALETTE = {
        new Color(192, 57, 43),   // red
        new Color(39, 174, 96),   // green
        new Color(41, 128, 185),  // blue
        new Color(142, 68, 173),  // purple
        new Color(243, 156, 18),  // orange
        new Color(44, 62, 80),    // dark blue
        new Color(22, 160, 133),  // teal
        new Color(211, 84, 0)     // dark orange
    };
    private JPanel chartsPanel;

    // ── Hover state tracking ──────────────────────────────────────────────────
    private boolean isHoveringOverChart = false;
    private double lastHoveredRawX = 0.0;
    private int lastMouseScreenX = 0;
    private int lastMouseScreenY = 0;
    private JLabel currentCursorOverlay = null;
    private XYPlot currentPlot = null;
    private XYSeriesCollection currentDataset = null;
    private ValueMarker currentHoverMarker = null;
    private int currentChartIndex = -1;
    
    // ── Chart markers for hover tracking ──────────────────────────────────────
    private ValueMarker altMarker;
    private ValueMarker velMarker;
    private ValueMarker accMarker;

    // ── Pinned markers (created by clicking) ──────────────────────────────────
    // Map from chart index to list of pinned x-values for that chart
    private final Map<Integer, List<Double>> pinnedXValues = new HashMap<>();
    private static final Color PINNED_MARKER_COLOR = new Color(255, 140, 0); // orange
    // Track which pinned marker is being hovered (null if none)
    private Double hoveredPinnedMarkerX = null;
    private int hoveredPinnedMarkerChartIdx = -1;
    private static final double MARKER_HOVER_TOLERANCE = 0.00625; // 1/16 of domain range

    // ── Playback speed ────────────────────────────────────────────────────────
    // Playback speed multiplier controlled by the speed slider.
    // Higher = faster simulation time.

    /**
     * Constructor receives the flight data branch and flight events from LiveSimListener.
     * The window can present any available flight data graph and switch between them.
     */
    public LiveSimWindow(FlightDataBranch dataBranch, List<FlightEvent> flightEvents) {
        this.dataBranch = dataBranch;
        this.time = safeList(dataBranch.get(FlightDataType.TYPE_TIME));
        this.flightEvents = flightEvents != null ? flightEvents : new ArrayList<>();

        for (DataType type : dataBranch.getTypes()) {
            if (type instanceof FlightDataType) {
                FlightDataType flightType = (FlightDataType) type;
                if (!FlightDataType.TYPE_TIME.equals(flightType)) {
                    List<Double> values = safeList(dataBranch.get(flightType));
                    if (!values.isEmpty()) {
                        availableTypes.add(flightType);
                        dataByType.put(flightType, values);
                    }
                }
            }
        }

        availableTypes.sort(Comparator.comparing(FlightDataType::getName));

        selectedTypes[0] = chooseType(FlightDataType.TYPE_ALTITUDE, FlightDataType.TYPE_ALTITUDE_ABOVE_SEA);
        selectedTypes[1] = chooseType(FlightDataType.TYPE_VELOCITY_Z, FlightDataType.TYPE_VELOCITY_TOTAL);
        selectedTypes[2] = chooseType(FlightDataType.TYPE_ACCELERATION_Z, FlightDataType.TYPE_ACCELERATION_TOTAL);

        // Initialize pinned markers
        for (int i = 0; i < 3; i++) {
            pinnedXValues.put(i, new ArrayList<>());
        }

        // Initialize event visibility map
        int colorIndex = 0;
        for (FlightEvent event : this.flightEvents) {
            String eventType = event.getType().toString();
            eventVisibility.put(eventType, false);
            eventMarkers.put(eventType, new ArrayList<>());
            eventColors.put(eventType, EVENT_PALETTE[colorIndex % EVENT_PALETTE.length]);
            colorIndex++;
        }

        buildUI();
    }

    /**
     * buildUI() constructs the entire window layout.
     * Called once from the constructor before the window is shown.
     */
    private void buildUI() {
        setTitle("Live Flight Viewer");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE); // don't kill the whole app on close
        setLayout(new BorderLayout());
        setSize(900, 700);
        setLocationRelativeTo(null); // center on screen

        // ── Create menu bar with chart selection and event toggles ───────────
        JMenuBar menuBar = new JMenuBar();
        JMenu chartsMenu = new JMenu("Charts");

        for (int chartIdx = 0; chartIdx < 3; chartIdx++) {
            JMenu chartMenu = new JMenu("Chart " + (chartIdx + 1));
            ButtonGroup group = new ButtonGroup();
            for (FlightDataType type : availableTypes) {
                String label = formatTypeLabel(type);
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(label);
                if (type.equals(selectedTypes[chartIdx])) {
                    item.setSelected(true);
                }
                final int idx = chartIdx;
                final FlightDataType selectedType = type;
                item.addActionListener(e -> setChartType(idx, selectedType));
                group.add(item);
                chartMenu.add(item);
            }
            chartsMenu.add(chartMenu);
        }

        JMenu optionsMenu = new JMenu("Events");
        for (FlightEvent event : flightEvents) {
            String eventType = event.getType().toString();
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(eventType);
            item.setSelected(false);
            item.addActionListener(e -> {
                boolean isSelected = item.isSelected();
                if (isSelected) {
                    // Check if event time is within revealed data
                    double eventTime = event.getTime();
                    double revealedUpToTime = time.isEmpty() ? 0 : (currentIndex < time.size() ? time.get(currentIndex - 1) : time.get(time.size() - 1));
                    if (eventTime > revealedUpToTime) {
                        JOptionPane.showMessageDialog(this, "Cannot enable event markers for unrevealed data", "Info", JOptionPane.INFORMATION_MESSAGE);
                        item.setSelected(false);
                        return;
                    }
                }
                eventVisibility.put(eventType, isSelected);
                updateEventMarkers();
            });
            optionsMenu.add(item);
        }
        menuBar.add(chartsMenu);
        menuBar.add(optionsMenu);
        setJMenuBar(menuBar);

        // ── Create the three charts ───────────────────────────────────────────
        // Each chart gets its own XYSeriesCollection (dataset wrapper).
        // We pass the empty series in now — data gets added during playback.
        chartsPanel = new JPanel(new GridLayout(3, 1, 0, 5));
        chartsPanel.add(buildChartPanel(0, selectedTypes[0]));
        chartsPanel.add(buildChartPanel(1, selectedTypes[1]));
        chartsPanel.add(buildChartPanel(2, selectedTypes[2]));

        add(chartsPanel, BorderLayout.CENTER);

        // ── Control panel at the bottom ───────────────────────────────────────
        add(buildControlPanel(), BorderLayout.SOUTH);
    }

    /**
     * buildChartPanel() is a helper that creates one JFreeChart line chart
     * wrapped in a Swing ChartPanel, ready to be added to the layout.
     *
     * @param chartIndex index of the chart (0=altitude, 1=velocity, 2=acceleration)
     * @param title      chart title shown at top
     * @param xLabel     x-axis label
     * @param yLabel     y-axis label
     * @param series     the XYSeries that will receive data during playback
     */
    private ChartPanel buildChartPanel(int chartIndex, FlightDataType selectedType) {
        XYSeries series = chartSeries[chartIndex];
        XYSeriesCollection dataset = new XYSeriesCollection(series);

        String title = selectedType != null ? formatTypeLabel(selectedType) : "Chart " + (chartIndex + 1);
        String yLabel = selectedType != null ? formatTypeLabel(selectedType) : "Value";

        // ChartFactory.createXYLineChart() builds a standard line chart.
        // We use VERTICAL orientation so x=time goes left to right.
        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Time (s)", yLabel,
                dataset,
                PlotOrientation.VERTICAL,
                false,  // no legend (series name is in the y-axis label)
                false,  // no tooltips
                false   // no URLs
        );

        // Style the chart line — make it thicker and a nice color
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        // true = draw lines, false = don't draw shapes (dots) at each point
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));
        Color seriesColor;
        switch (chartIndex) {
            case 0:
                seriesColor = new Color(0, 120, 215); // blue
                break;
            case 1:
                seriesColor = new Color(39, 174, 96); // green
                break;
            case 2:
                seriesColor = new Color(142, 68, 173); // purple
                break;
            default:
                seriesColor = new Color(0, 120, 215);
                break;
        }
        renderer.setSeriesPaint(0, seriesColor);
        plot.setRenderer(renderer);

        // Light background for readability
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        // Add a transparent vertical hover marker for easier x-axis tracking.
        ValueMarker hoverMarker = new ValueMarker(0.0, new Color(0, 0, 0, 0), new BasicStroke(2.0f));
        plot.addDomainMarker(hoverMarker);
        
        // Store marker reference based on chart index
        if (chartIndex == 0) {
            altMarker = hoverMarker;
        } else if (chartIndex == 1) {
            velMarker = hoverMarker;
        } else if (chartIndex == 2) {
            accMarker = hoverMarker;
        }

        // Fix the axis ranges from the beginning so they stay constant while the line draws.
        double xMin = time.isEmpty() ? 0 : time.get(0);
        double xMax = time.isEmpty() ? 1 : time.get(time.size() - 1);
        double yMin = 0;
        double yMax = 1;
        List<Double> rangeValues = selectedType != null ? dataByType.get(selectedType) : null;
        double[] range = computeFiniteRange(rangeValues);
        if (range != null) {
            yMin = range[0];
            yMax = range[1];
            if (Double.compare(yMin, yMax) == 0) {
                yMin -= 1;
                yMax += 1;
            } else {
                double padding = Math.max(0.05 * (yMax - yMin), 1e-3);
                yMin -= padding;
                yMax += padding;
            }
        }

        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setAutoRange(false);
        domainAxis.setRange(xMin, xMax);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setAutoRange(false);
        rangeAxis.setRange(yMin, yMax);

        ChartPanel panel = new ChartPanel(chart);
        chartPanels[chartIndex] = panel;
        panel.setPreferredSize(new Dimension(900, 200));
        panel.setLayout(null);
        panel.setFocusable(true);
        
        JLabel cursorOverlay = new JLabel();
        cursorOverlay.setOpaque(true);
        cursorOverlay.setBackground(new Color(255, 255, 255, 220));
        cursorOverlay.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        cursorOverlay.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        cursorOverlay.setVisible(false);
        cursorOverlay.setSize(120, 22);
        panel.add(cursorOverlay);
        
        // Add key listener for deleting pinned markers
        panel.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if ((e.getKeyCode() == java.awt.event.KeyEvent.VK_DELETE || e.getKeyCode() == java.awt.event.KeyEvent.VK_BACK_SPACE) &&
                    hoveredPinnedMarkerX != null && hoveredPinnedMarkerChartIdx == chartIndex) {
                    List<Double> pinnedList = pinnedXValues.get(chartIndex);
                    pinnedList.remove((Double) hoveredPinnedMarkerX);
                    hoveredPinnedMarkerX = null;
                    hoveredPinnedMarkerChartIdx = -1;
                    updateEventMarkers();
                }
            }
        });
        
        panel.addChartMouseListener(new ChartMouseListener() {
            @Override
            public void chartMouseClicked(ChartMouseEvent event) {
                Point2D p = panel.translateScreenToJava2D(event.getTrigger().getPoint());
                Rectangle2D plotArea = panel.getScreenDataArea();
                XYPlot plot = (XYPlot) chart.getPlot();
                if (plotArea != null && plotArea.contains(p)) {
                    double clickX = plot.getDomainAxis().java2DToValue(p.getX(), plotArea, plot.getDomainAxisEdge());
                    handleChartClick(chartIndex, clickX);
                }
            }

            @Override
            public void chartMouseMoved(ChartMouseEvent event) {
                Point2D p = panel.translateScreenToJava2D(event.getTrigger().getPoint());
                Rectangle2D plotArea = panel.getScreenDataArea();
                XYPlot plot = (XYPlot) chart.getPlot();
                if (plotArea != null && plotArea.contains(p)) {
                    double rawX = plot.getDomainAxis().java2DToValue(p.getX(), plotArea, plot.getDomainAxisEdge());
                    isHoveringOverChart = true;
                    lastHoveredRawX = rawX;
                    lastMouseScreenX = event.getTrigger().getX();
                    lastMouseScreenY = event.getTrigger().getY();
                    currentCursorOverlay = cursorOverlay;
                    currentPlot = plot;
                    currentDataset = (XYSeriesCollection) plot.getDataset();
                    // Determine which marker to use based on chart index
                    if (chartIndex == 0) {
                        currentHoverMarker = altMarker;
                    } else if (chartIndex == 1) {
                        currentHoverMarker = velMarker;
                    } else if (chartIndex == 2) {
                        currentHoverMarker = accMarker;
                    }
                    currentChartIndex = chartIndex;
                    
                    // Check if hovering over a pinned marker
                    double domainRange = plot.getDomainAxis().getUpperBound() - plot.getDomainAxis().getLowerBound();
                    double tolerance = domainRange * MARKER_HOVER_TOLERANCE;
                    List<Double> pinnedList = pinnedXValues.getOrDefault(chartIndex, new ArrayList<>());
                    hoveredPinnedMarkerX = null;
                    hoveredPinnedMarkerChartIdx = -1;
                    for (Double pinnedX : pinnedList) {
                        if (Math.abs(rawX - pinnedX) < tolerance) {
                            hoveredPinnedMarkerX = pinnedX;
                            hoveredPinnedMarkerChartIdx = chartIndex;
                            panel.requestFocus(); // request focus for key events
                            break;
                        }
                    }
                    
                    updateHoverDisplay(event.getTrigger().getX(), event.getTrigger().getY(), panel);
                } else {
                    isHoveringOverChart = false;
                    cursorOverlay.setVisible(false);
                    currentChartIndex = -1;
                    hoveredPinnedMarkerX = null;
                    hoveredPinnedMarkerChartIdx = -1;
                    if (currentHoverMarker != null) {
                        currentHoverMarker.setPaint(new Color(0, 0, 0, 0));
                    }
                }
            }
        });
        return panel;
    }

    private List<Double> safeList(List<Double> list) {
        return list != null ? list : new ArrayList<>();
    }

    private FlightDataType chooseType(FlightDataType preferred, FlightDataType fallback) {
        if (availableTypes.contains(preferred)) {
            return preferred;
        }
        if (availableTypes.contains(fallback)) {
            return fallback;
        }
        return availableTypes.isEmpty() ? null : availableTypes.get(0);
    }

    private String formatTypeLabel(FlightDataType type) {
        if (type == null) {
            return "";
        }
        String unit = formatUnitString(type);
        if (unit.isEmpty()) {
            return type.getName();
        }
        return String.format("%s (%s)", type.getName(), unit);
    }

    private String formatUnitString(FlightDataType type) {
        if (type == null) {
            return "";
        }
        String unit = getCorrectUnitSymbol(type);
        if (unit != null && !unit.isEmpty()) {
            return unit;
        }
        // Do not fall back to UnitGroup.toString() if unit is empty
        return "";
    }

    private String getCorrectUnitSymbol(FlightDataType type) {
        if (type == null) {
            return "";
        }
        // Correct common units based on OpenRocket data types
        if (type.equals(FlightDataType.TYPE_ALTITUDE) || type.equals(FlightDataType.TYPE_ALTITUDE_ABOVE_SEA) ||
            type.equals(FlightDataType.TYPE_POSITION_X) || type.equals(FlightDataType.TYPE_POSITION_Y) ||
            type.equals(FlightDataType.TYPE_POSITION_XY) || type.equals(FlightDataType.TYPE_CP_LOCATION) ||
            type.equals(FlightDataType.TYPE_CG_LOCATION)) {
            return "m"; // meters
        }
        if (type.equals(FlightDataType.TYPE_VELOCITY_Z) || type.equals(FlightDataType.TYPE_VELOCITY_TOTAL) ||
            type.equals(FlightDataType.TYPE_VELOCITY_XY) || type.equals(FlightDataType.TYPE_WIND_VELOCITY)) {
            return "m/s"; // meters per second
        }
        if (type.equals(FlightDataType.TYPE_ACCELERATION_Z) || type.equals(FlightDataType.TYPE_ACCELERATION_TOTAL) ||
            type.equals(FlightDataType.TYPE_ACCELERATION_XY) || type.equals(FlightDataType.TYPE_CORIOLIS_ACCELERATION)) {
            return "m/s²"; // meters per second squared
        }
        if (type.equals(FlightDataType.TYPE_MASS) || type.equals(FlightDataType.TYPE_MOTOR_MASS)) {
            return "kg"; // kilograms
        }
        if (type.equals(FlightDataType.TYPE_THRUST_FORCE) || type.equals(FlightDataType.TYPE_DRAG_FORCE)) {
            return "N"; // newtons
        }
        if (type.equals(FlightDataType.TYPE_AOA) || type.equals(FlightDataType.TYPE_ORIENTATION_THETA) ||
            type.equals(FlightDataType.TYPE_ORIENTATION_PHI)) {
            return "°"; // degrees
        }
        if (type.equals(FlightDataType.TYPE_LATITUDE) || type.equals(FlightDataType.TYPE_LONGITUDE)) {
            return "°"; // degrees
        }
        if (type.equals(FlightDataType.TYPE_ROLL_RATE) || type.equals(FlightDataType.TYPE_PITCH_RATE) ||
            type.equals(FlightDataType.TYPE_YAW_RATE)) {
            return "°/s"; // degrees per second
        }
        if (type.equals(FlightDataType.TYPE_TIME)) {
            return "s"; // seconds
        }
        if (type.equals(FlightDataType.TYPE_AIR_PRESSURE)) {
            return "Pa"; // pascals
        }
        if (type.equals(FlightDataType.TYPE_AIR_TEMPERATURE)) {
            return "K"; // kelvin
        }
        if (type.equals(FlightDataType.TYPE_AIR_DENSITY)) {
            return "kg/m³"; // kilograms per cubic meter
        }
        if (type.equals(FlightDataType.TYPE_MACH_NUMBER)) {
            return ""; // dimensionless
        }
        if (type.equals(FlightDataType.TYPE_REYNOLDS_NUMBER)) {
            return ""; // dimensionless
        }
        if (type.equals(FlightDataType.TYPE_STABILITY)) {
            return ""; // dimensionless (calibers)
        }
        if (type.equals(FlightDataType.TYPE_REFERENCE_LENGTH) || type.equals(FlightDataType.TYPE_REFERENCE_AREA)) {
            return "m"; // meters
        }
        if (type.equals(FlightDataType.TYPE_WIND_DIRECTION)) {
            return "°"; // degrees
        }
        if (type.equals(FlightDataType.TYPE_TIME_STEP) || type.equals(FlightDataType.TYPE_COMPUTATION_TIME)) {
            return "s"; // seconds
        }
        // For coefficients, they are dimensionless
        if (type.getName().contains("coefficient") || type.getName().contains("Coefficient")) {
            return ""; // dimensionless
        }
        // Fallback to the type's symbol
        String symbol = type.getSymbol();
        return (symbol != null) ? symbol : "";
    }



    private double getSeriesValue(int chartIndex, int dataIndex) {
        FlightDataType type = selectedTypes[chartIndex];
        if (type == null) {
            return Double.NaN;
        }
        List<Double> values = dataByType.get(type);
        if (values == null || dataIndex < 0 || dataIndex >= values.size()) {
            return Double.NaN;
        }
        double value = values.get(dataIndex);
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private double[] computeFiniteRange(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Double value : values) {
            if (value != null && Double.isFinite(value)) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
            return null;
        }
        return new double[] { min, max };
    }

    private void updateChartRange(int chartIndex) {
        FlightDataType type = selectedTypes[chartIndex];
        if (type == null || chartPanels[chartIndex] == null) {
            return;
        }
        List<Double> values = dataByType.get(type);
        if (values == null || values.isEmpty()) {
            return;
        }
        double[] range = computeFiniteRange(values);
        if (range == null) {
            return;
        }
        double yMin = range[0];
        double yMax = range[1];
        if (Double.compare(yMin, yMax) == 0) {
            yMin -= 1;
            yMax += 1;
        } else {
            double padding = Math.max(0.05 * (yMax - yMin), 1e-3);
            yMin -= padding;
            yMax += padding;
        }

        ChartPanel panel = chartPanels[chartIndex];
        XYPlot plot = panel.getChart().getXYPlot();
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setRange(yMin, yMax);
    }

    private void setChartType(int chartIndex, FlightDataType chartType) {
        if (chartType == null || chartType.equals(selectedTypes[chartIndex])) {
            return;
        }
        selectedTypes[chartIndex] = chartType;

        ChartPanel panel = chartPanels[chartIndex];
        if (panel == null) {
            return;
        }

        // Clear pinned markers when changing chart type
        pinnedXValues.get(chartIndex).clear();

        JFreeChart chart = panel.getChart();
        XYPlot plot = chart.getXYPlot();
        XYSeries series = chartSeries[chartIndex];
        series.clear();

        for (int i = 0; i < currentIndex && i < time.size(); i++) {
            double t = time.get(i);
            double val = getSeriesValue(chartIndex, i);
            if (!Double.isNaN(val)) {
                series.add(t, val);
            }
        }

        chart.setTitle(formatTypeLabel(chartType));
        ((NumberAxis) plot.getRangeAxis()).setLabel(formatTypeLabel(chartType));
        updateChartRange(chartIndex);
        updateEventMarkers();
    }

    private void handleChartClick(int chartIndex, double clickX) {
        // Clamp clicks to the last revealed time so marker dialogs still open on blank areas.
        double revealedUpToTime = time.isEmpty() ? clickX : time.get(Math.max(0, currentIndex - 1));
        if (clickX > revealedUpToTime) {
            clickX = revealedUpToTime;
        }
        
        // Show input dialog with pre-filled current x-value
        String currentXStr = String.format("%.2f", clickX);
        String input = JOptionPane.showInputDialog(this, "Enter x-value (time) for marker:", currentXStr);
        
        if (input != null && !input.trim().isEmpty()) {
            try {
                double xValue = Double.parseDouble(input.trim());
                
                if (!time.isEmpty()) {
                    double minTime = time.get(0);
                    double maxTime = time.get(Math.max(0, currentIndex - 1));
                    xValue = Math.max(minTime, Math.min(maxTime, xValue));
                }
                
                List<Double> pinnedList = pinnedXValues.get(chartIndex);
                if (!pinnedList.contains(xValue)) {
                    pinnedList.add(xValue);
                    pinnedList.sort(null);
                    updateEventMarkers(); // redraw with new pinned marker
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Invalid number format", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private double snapToSeriesY(XYSeries series, double x) {
        if (series.isEmpty()) {
            return Double.NaN;
        }
        int itemCount = series.getItemCount();
        if (itemCount == 1) {
            return series.getY(0).doubleValue();
        }

        double firstX = series.getX(0).doubleValue();
        double lastX = series.getX(itemCount - 1).doubleValue();
        if (x <= firstX) {
            return series.getY(0).doubleValue();
        }
        if (x >= lastX) {
            return series.getY(itemCount - 1).doubleValue();
        }

        for (int i = 1; i < itemCount; i++) {
            double x0 = series.getX(i - 1).doubleValue();
            double x1 = series.getX(i).doubleValue();
            if (x0 <= x && x <= x1) {
                double y0 = series.getY(i - 1).doubleValue();
                double y1 = series.getY(i).doubleValue();
                if (x1 == x0) {
                    return y0;
                }
                return y0 + (y1 - y0) * ((x - x0) / (x1 - x0));
            }
        }

        return series.getY(itemCount - 1).doubleValue();
    }

    private double clampToVisibleSeriesX(XYSeries series, double x) {
        if (series.isEmpty()) {
            return x;
        }
        int lastIndex = series.getItemCount() - 1;
        double firstX = series.getX(0).doubleValue();
        double lastX = series.getX(lastIndex).doubleValue();
        return Math.max(firstX, Math.min(x, lastX));
    }

    private void updateHoverDisplay(int mouseScreenX, int mouseScreenY, ChartPanel panel) {
        if (!isHoveringOverChart || currentCursorOverlay == null || currentDataset == null) {
            return;
        }
        
        XYSeries hoveredSeries = currentDataset.getSeries(0);
        if (hoveredSeries == null) {
            return;
        }
        
        double visibleX = clampToVisibleSeriesX(hoveredSeries, lastHoveredRawX);
        double y = snapToSeriesY(hoveredSeries, visibleX);
        String yUnit = currentChartIndex >= 0 ? formatUnitString(selectedTypes[currentChartIndex]) : "";
        String text;
        if (yUnit.isEmpty()) {
            text = String.format("%.2f s, %.2f", visibleX, y);
        } else {
            text = String.format("%.2f s, %.2f %s", visibleX, y, yUnit);
        }
        currentCursorOverlay.setText(text);
        
        // Adjust size based on text length
        java.awt.FontMetrics fm = currentCursorOverlay.getFontMetrics(currentCursorOverlay.getFont());
        int textWidth = fm.stringWidth(text) + 10; // add padding
        int textHeight = fm.getHeight() + 4;
        currentCursorOverlay.setSize(Math.max(textWidth, 120), Math.max(textHeight, 22)); // minimum size
        
        int labelX = mouseScreenX + 10;
        int labelY = mouseScreenY - currentCursorOverlay.getHeight() - 6;
        if (panel != null && labelX + currentCursorOverlay.getWidth() > panel.getWidth()) {
            labelX = panel.getWidth() - currentCursorOverlay.getWidth() - 4;
        }
        if (labelY < 0) {
            labelY = mouseScreenY + 10;
        }
        currentCursorOverlay.setLocation(labelX, labelY);
        currentCursorOverlay.setVisible(true);
        
        if (currentHoverMarker != null) {
            currentHoverMarker.setValue(visibleX);
            currentHoverMarker.setPaint(new Color(0, 0, 0, 80));
        }
    }

    /**
     * buildControlPanel() creates the bottom bar with:
     * - a time readout label
     * - a play/pause button
     * - a speed slider
     */
    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        // Time display — shows current simulation time as playback progresses
        timeLabel = new JLabel("T+0.00s");
        timeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        panel.add(timeLabel);

        panel.add(Box.createHorizontalStrut(10)); // spacer

        // Play/Pause button — toggles the timer on and off, and becomes Replay when the animation is done.
        playPauseButton = new JButton("Pause");
        playPauseButton.addActionListener(e -> {
            if ("Replay".equals(playPauseButton.getText())) {
                replay();
            } else {
                togglePlayPause();
            }
        });
        panel.add(playPauseButton);

        // Speed label and slider
        panel.add(new JLabel("Speed:"));

        // Slider goes 1x to 10x playback speed
        speedSlider = new JSlider(1, 10, 1);
        speedSlider.setMajorTickSpacing(3);
        speedSlider.setMinorTickSpacing(1);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        speedSlider.setPreferredSize(new Dimension(200, 45));
        speedSlider.addChangeListener(e -> {
            // Update playback speed multiplier whenever the slider moves
            playbackSpeed = speedSlider.getValue();
        });
        panel.add(speedSlider);

        // Total flight time display
        if (!time.isEmpty()) {
            double totalTime = time.get(time.size() - 1);
            panel.add(new JLabel(String.format("  Total flight time: %.2fs", totalTime)));
        }

        return panel;
    }

    /**
     * startPlayback() creates and starts the Swing Timer that drives the animation.
     *
     * javax.swing.Timer fires an ActionEvent on the EDT at a fixed interval.
     * Each tick we add the next N data points to the chart series.
     *
     * Call this after setVisible(true) so the charts are already on screen.
     */
    public void startPlayback() {
        // 50ms per tick = 20 ticks per second = smooth animation
        // At 1x speed with typical OR data (~100 points/second of flight),
        // this plays back at roughly real-time speed
        playbackTimer = new Timer(50, e -> tick());
        playbackTimer.start();
    }

    /**
     * tick() is called every 50ms by the playback timer.
     * It adds the next batch of data points to all three chart series.
     */
    private void tick() {
        if (currentIndex >= time.size()) {
            playbackTimer.stop();
            playPauseButton.setText("Replay");
            return;
        }

        // Advance simulated playback time based on real time elapsed.
        double deltaSeconds = playbackTimer.getDelay() / 1000.0;
        playbackTime += deltaSeconds * playbackSpeed;

        // Add all points that fall within the new playback time.
        while (currentIndex < time.size() && time.get(currentIndex) <= playbackTime) {
            double t = time.get(currentIndex);
            double val0 = getSeriesValue(0, currentIndex);
            double val1 = getSeriesValue(1, currentIndex);
            double val2 = getSeriesValue(2, currentIndex);
            if (!Double.isNaN(val0)) {
                altSeries.add(t, val0);
            }
            if (!Double.isNaN(val1)) {
                velSeries.add(t, val1);
            }
            if (!Double.isNaN(val2)) {
                accSeries.add(t, val2);
            }
            currentIndex++;
        }

        double displayTime = playbackTime;
        if (displayTime > time.get(time.size() - 1)) {
            displayTime = time.get(time.size() - 1);
        }
        timeLabel.setText(String.format("T+%.2fs", displayTime));

        if (currentIndex >= time.size()) {
            playbackTimer.stop();
            playPauseButton.setText("Replay");
        }

        // Update hover display if hovering to snap to new data points
        if (isHoveringOverChart) {
            updateHoverDisplay(lastMouseScreenX, lastMouseScreenY, null);
        }
    }

    /**
     * togglePlayPause() pauses or resumes the playback timer.
     */
    private void togglePlayPause() {
        if (playbackTimer.isRunning()) {
            playbackTimer.stop();
            playPauseButton.setText("Play");
        } else {
            playbackTimer.start();
            playPauseButton.setText("Pause");
        }
    }

    /**
     * replay() resets everything and starts the animation over from the beginning.
     */
    private void replay() {
        // Clear all data from the series so the charts go blank
        altSeries.clear();
        velSeries.clear();
        accSeries.clear();

        // Reset the index, playback time, and time label
        currentIndex = 0;
        playbackTime = 0.0;
        timeLabel.setText("T+0.00s");

        // Restart the timer
        playPauseButton.setText("Pause");
        playbackTimer.start();
    }

    /**
     * updateEventMarkers() updates the visibility of flight event markers across all charts.
     */
    private void updateEventMarkers() {
        if (chartsPanel == null) return;
        
        // Get all chart plots from the panels
        Component[] components = chartsPanel.getComponents();
        XYSeries[] seriesArray = {altSeries, velSeries, accSeries};
        
        for (int chartIdx = 0; chartIdx < components.length; chartIdx++) {
            Component comp = components[chartIdx];
            if (comp instanceof ChartPanel) {
                ChartPanel chartPanel = (ChartPanel) comp;
                JFreeChart chart = chartPanel.getChart();
                XYPlot plot = chart.getXYPlot();
                
                // Clear event markers and annotations, but keep the hover marker handled separately.
                plot.clearDomainMarkers();
                plot.clearAnnotations();

                // Re-add event markers based on visibility
                XYSeries currentSeries = seriesArray[chartIdx];
                NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
                double yTop = rangeAxis.getUpperBound();
                double yBottom = rangeAxis.getLowerBound();
                double yMargin = Math.max(0.05 * (yTop - yBottom), 1.0);
                double annotationY = yTop - yMargin;

                for (FlightEvent event : flightEvents) {
                    String eventType = event.getType().toString();
                    if (eventVisibility.getOrDefault(eventType, false)) {
                        double eventTime = event.getTime();
                        Color eventColor = eventColors.getOrDefault(eventType, new Color(100, 100, 100, 150));
                        double eventY = snapToSeriesY(currentSeries, eventTime);

                        // Create semi-transparent dotted line marker with event-specific color.
                        ValueMarker marker = new ValueMarker(eventTime);
                        marker.setPaint(new Color(eventColor.getRed(), eventColor.getGreen(), eventColor.getBlue(), 170));
                        marker.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                                0f, new float[]{5f}, 0f)); // dotted line
                        plot.addDomainMarker(marker);

                        // Add title annotation at the top of the graph.
                        String yUnit = selectedTypes[chartIdx] != null ? formatUnitString(selectedTypes[chartIdx]) : "";
                        String label;
                        if (yUnit.isEmpty()) {
                            label = String.format("%s%n(%.2f s, %.2f)", eventType, eventTime, eventY);
                        } else {
                            label = String.format("%s%n(%.2f s, %.2f %s)", eventType, eventTime, eventY, yUnit);
                        }
                        XYTextAnnotation annotation = new XYTextAnnotation(label, eventTime, annotationY);
                        double xMin = plot.getDomainAxis().getLowerBound();
                        double xMax = plot.getDomainAxis().getUpperBound();
                        double midpoint = xMin + (xMax - xMin) / 2.0;
                        TextAnchor anchor = TextAnchor.TOP_LEFT;
                        if (eventTime > midpoint) {
                            anchor = TextAnchor.TOP_RIGHT;
                        }
                        annotation.setTextAnchor(anchor);
                        annotation.setRotationAnchor(anchor);
                        annotation.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                        annotation.setBackgroundPaint(new Color(255, 255, 255, 220));
                        annotation.setPaint(eventColor.darker());
                        plot.addAnnotation(annotation);
                    }
                }

                // Re-add pinned markers created by clicking
                List<Double> pinnedList = pinnedXValues.getOrDefault(chartIdx, new ArrayList<>());
                double xMin = plot.getDomainAxis().getLowerBound();
                double xMax = plot.getDomainAxis().getUpperBound();
                double xRange = xMax - xMin;
                double minPinDistance = xRange * 0.1; // 10% of domain range
                
                for (int pinnedIdx = 0; pinnedIdx < pinnedList.size(); pinnedIdx++) {
                    Double pinnedX = pinnedList.get(pinnedIdx);
                    double pinnedY = snapToSeriesY(currentSeries, pinnedX);
                    
                    // Create solid orange line marker for pinned markers
                    ValueMarker pinnedMarker = new ValueMarker(pinnedX);
                    pinnedMarker.setPaint(PINNED_MARKER_COLOR);
                    pinnedMarker.setStroke(new BasicStroke(2.0f)); // solid line
                    plot.addDomainMarker(pinnedMarker);
                    
                    // Add annotation with coordinates
                    String yUnit = selectedTypes[chartIdx] != null ? formatUnitString(selectedTypes[chartIdx]) : "";
                    String label;
                    if (yUnit.isEmpty()) {
                        label = String.format("(%.2f s, %.2f)", pinnedX, pinnedY);
                    } else {
                        label = String.format("(%.2f s, %.2f %s)", pinnedX, pinnedY, yUnit);
                    }
                    
                    // Stagger annotations vertically if markers are close together
                    // Find position within cluster of close markers
                    int closeBefore = 0;
                    int closeAfter = 0;
                    for (int j = 0; j < pinnedList.size(); j++) {
                        if (j != pinnedIdx) {
                            double dist = Math.abs(pinnedList.get(j) - pinnedX);
                            if (dist < minPinDistance) {
                                if (j < pinnedIdx) {
                                    closeBefore++;
                                } else {
                                    closeAfter++;
                                }
                            }
                        }
                    }
                    
                    double annotationYOffset = annotationY - 2 * yMargin;
                    if (closeBefore + closeAfter > 0) {
                        // Position within cluster: 0 = first, 1 = second, etc.
                        int posInCluster = closeBefore;
                        int clusterSize = closeBefore + closeAfter + 1;
                        double offsetPerMarker = 2.5 * yMargin;
                        
                        // Calculate total vertical space needed for cluster
                        double clusterHeight = (clusterSize - 1) * offsetPerMarker;
                        double minAnnotationY = yBottom + yMargin;
                        double maxAnnotationY = yTop - yMargin;
                        double availableSpace = maxAnnotationY - minAnnotationY;
                        
                        // If cluster doesn't fit, scale down the spacing
                        if (clusterHeight > availableSpace) {
                            offsetPerMarker = availableSpace / Math.max(1, clusterSize - 1);
                        }
                        
                        // Center the cluster around the base position
                        annotationYOffset += (posInCluster - (clusterSize - 1) / 2.0) * offsetPerMarker;
                    }
                    
                    // Clamp to stay within plot bounds, using the default single-marker height as the maximum.
                    double maxAnnotationY = annotationY;
                    double minAnnotationY = yBottom + yMargin;
                    annotationYOffset = Math.max(minAnnotationY, Math.min(maxAnnotationY, annotationYOffset));
                    
                    // Determine horizontal offset to avoid text overlap when anchoring changes
                    double midpoint = xMin + xRange / 2.0;
                    TextAnchor anchor = TextAnchor.TOP_LEFT;
                    if (pinnedX > midpoint) {
                        anchor = TextAnchor.TOP_RIGHT;
                    }
                    
                    double annotationXOffset = pinnedX;
                    if (closeBefore + closeAfter > 0) {
                        // Apply horizontal offset for clustered markers
                        int posInCluster = closeBefore;
                        int clusterSize = closeBefore + closeAfter + 1;
                        double horizontalSpacing = xRange * 0.02; // 2% of domain range
                        
                        if (pinnedX > midpoint) {
                            // Right side: spread markers to the right
                            annotationXOffset += (posInCluster - (clusterSize - 1) / 2.0) * horizontalSpacing;
                        } else {
                            // Left side: spread markers to the left
                            annotationXOffset -= (posInCluster - (clusterSize - 1) / 2.0) * horizontalSpacing;
                        }
                    }
                    
                    XYTextAnnotation pinnedAnnotation = new XYTextAnnotation(label, annotationXOffset, annotationYOffset);
                    pinnedAnnotation.setTextAnchor(anchor);
                    pinnedAnnotation.setRotationAnchor(anchor);
                    pinnedAnnotation.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                    pinnedAnnotation.setBackgroundPaint(new Color(255, 255, 255, 220));
                    pinnedAnnotation.setPaint(PINNED_MARKER_COLOR);
                    plot.addAnnotation(pinnedAnnotation);
                }

                // Re-add the hover marker after event markers so it remains visible.
                if (chartIdx == 0 && altMarker != null) {
                    plot.addDomainMarker(altMarker);
                } else if (chartIdx == 1 && velMarker != null) {
                    plot.addDomainMarker(velMarker);
                } else if (chartIdx == 2 && accMarker != null) {
                    plot.addDomainMarker(accMarker);
                }
            }
        }
    }
}
