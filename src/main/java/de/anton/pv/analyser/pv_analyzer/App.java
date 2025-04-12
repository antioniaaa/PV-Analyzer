package de.anton.pv.analyser.pv_analyzer;

import de.anton.pv.analyser.pv_analyzer.controller.AppController;
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel;
import de.anton.pv.analyser.pv_analyzer.view.MainView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

/**
 * Main application class. Sets up the MVC components and starts the GUI. This...
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            // Use system look and feel for better native appearance
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            //Testweise Crossplattform
//            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            logger.debug("System Look and Feel set successfully.");
        } catch (UnsupportedLookAndFeelException | ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            logger.warn("Could not set system look and feel, using default.", e);
        } catch (Exception e) {
             logger.error("Unexpected error setting Look and Feel.", e);
        }

        // Schedule GUI creation on the Event Dispatch Thread
        SwingUtilities.invokeLater(App::createAndShowGUI);
    }

    /**
     * Creates the model, view, and controller, links them, and shows the main window.
     * Must be called on the Event Dispatch Thread.
     */
    private static void createAndShowGUI() {
        // Ensure running on the EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            logger.error("createAndShowGUI called from wrong thread: {}", Thread.currentThread().getName());
             // Exit immediately if called from wrong thread during startup
             System.exit(1);
             // return; // Not reachable after exit
        }

        try {
            logger.info("Creating application components...");

            // 1. Create the Model
            AnalysisModel analysisModel = new AnalysisModel();
            logger.debug("AnalysisModel created.");

            // 2. Create the View, passing the available variables from the model
            //    *** This is the corrected line ***
            MainView mainView = new MainView(analysisModel.getAvailableVariables());
            logger.debug("MainView created.");

            // 3. Create the Controller, linking Model and View
            AppController controller = new AppController(analysisModel, mainView);
            logger.debug("AppController created and linked MVC components.");

            // 4. Configure and show the main window
            mainView.setLocationRelativeTo(null); // Center on screen
            mainView.setVisible(true);
            logger.info("Application GUI started and shown.");

        } catch (Exception e) {
            // Catch critical startup errors
            logger.error("Critical error during application startup", e);
            // Show error message to the user before exiting
            JOptionPane.showMessageDialog(null,
                    "Ein kritischer Fehler ist beim Start der Anwendung aufgetreten:\n" +
                            e.getClass().getSimpleName() + ": " + e.getMessage() +
                            "\n\nDie Anwendung wird beendet.",
                    "Startfehler", JOptionPane.ERROR_MESSAGE);
            System.exit(1); // Terminate application
        }
    }
}