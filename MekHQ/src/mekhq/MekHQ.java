/*
 * MekHQ.java
 *
 * Copyright (c) 2009 Jay Lawson <jaylawson39 at yahoo.com>. All rights reserved.
 * Copyright (c) 2020 The MegaMek Team.
 *
 * This file is part of MekHQ.
 *
 * MekHQ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MekHQ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MekHQ.  If not, see <http://www.gnu.org/licenses/>.
 */
package mekhq;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.ResourceBundle;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;

import megamek.MegaMek;
import megamek.client.Client;
import megamek.common.event.EventBus;
import megamek.common.event.GameBoardChangeEvent;
import megamek.common.event.GameBoardNewEvent;
import megamek.common.event.GameCFREvent;
import megamek.common.event.GameEndEvent;
import megamek.common.event.GameEntityChangeEvent;
import megamek.common.event.GameEntityNewEvent;
import megamek.common.event.GameEntityNewOffboardEvent;
import megamek.common.event.GameEntityRemoveEvent;
import megamek.common.event.GameListener;
import megamek.common.event.GameMapQueryEvent;
import megamek.common.event.GameNewActionEvent;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GamePlayerChangeEvent;
import megamek.common.event.GamePlayerChatEvent;
import megamek.common.event.GamePlayerConnectedEvent;
import megamek.common.event.GamePlayerDisconnectedEvent;
import megamek.common.event.GameReportEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.event.GameVictoryEvent;
import megamek.common.event.MMEvent;
import megamek.common.logging.DefaultMmLogger;
import megamek.common.logging.LogLevel;
import megamek.common.logging.MMLogger;
import megamek.common.preference.PreferenceManager;
import megamek.common.util.EncodeControl;
import megamek.server.Server;
import mekhq.campaign.Campaign;
import mekhq.campaign.CampaignController;
import mekhq.campaign.ResolveScenarioTracker;
import mekhq.campaign.event.ScenarioResolvedEvent;
import mekhq.campaign.handler.XPHandler;
import mekhq.campaign.mission.AtBContract;
import mekhq.campaign.mission.AtBScenario;
import mekhq.campaign.mission.Scenario;
import mekhq.campaign.unit.Unit;
import mekhq.gui.CampaignGUI;
import mekhq.gui.StartUpGUI;
import mekhq.gui.dialog.LaunchGameDialog;
import mekhq.gui.dialog.ResolveScenarioWizardDialog;
import mekhq.gui.dialog.RetirementDefectionDialog;
import mekhq.gui.preferences.StringPreference;
import mekhq.gui.utilities.ObservableString;
import mekhq.preferences.MekHqPreferences;
import mekhq.preferences.PreferencesNode;
import mekhq.service.AutosaveService;
import mekhq.service.IAutosaveService;

/**
 * The main class of the application.
 */
public class MekHQ implements GameListener {
	// TODO : This is intended as a debug/production type thing.
	// TODO : So it should be backed down to 1 for releases...
	// TODO : It's intended for 1 to be critical, 3 to be typical, and 5 to be debug/informational.
	public static int VERBOSITY_LEVEL = 5;
	public static String CAMPAIGN_DIRECTORY = "./campaigns/";
	public static String PREFERENCES_FILE = "mmconf/mekhq.preferences";
	public static String PRESET_DIR = "./mmconf/mhqPresets/";
	public static String DEFAULT_LOG_FILE_NAME = "mekhqlog.txt";

	private static final EventBus EVENT_BUS = new EventBus();

	private static Frame window;
    private static ObservableString selectedTheme;

    private static MMLogger logger = null;
	private static MekHqPreferences preferences = null;

    // Directory options
    private static ObservableString personnelDirectory;
    private static ObservableString campaignOptionsDirectory;
    private static ObservableString partsDirectory;
    private static ObservableString planetsDirectory;
    private static ObservableString starMapsDirectory;
    private static ObservableString unitsDirectory;
    private static ObservableString campaignsDirectory;
    private static ObservableString scenarioTemplatesDirectory;
    private static ObservableString financesDirectory;

	//stuff related to MM games
    private Server myServer = null;
    private GameThread gameThread = null;
    private Scenario currentScenario = null;
    private Client client = null;

    //the actual campaign - this is where the good stuff is
    private CampaignController campaignController;
    private CampaignGUI campaigngui;

    private IconPackage iconPackage = new IconPackage();

    private final IAutosaveService autosaveService;

	/**
	 * Converts the MekHQ {@link #VERBOSITY_LEVEL} to {@link LogLevel}.
	 *
	 * @param verbosity The MekHQ verbosity to be converted.
	 * @return The equivalent LogLevel.
	 */
	private static LogLevel verbosityToLogLevel(final int verbosity) {
		if (verbosity < 0) {
			return LogLevel.OFF;
		}
		switch (verbosity) {
			case 0:
				return LogLevel.FATAL;
			case 1:
				return LogLevel.ERROR;
			case 2:
				return LogLevel.WARNING;
			case 3:
				return LogLevel.INFO;
			case 4:
				return LogLevel.DEBUG;
			case 5:
				return LogLevel.TRACE;
		}
		return LogLevel.INFO;
	}

	/**
	 * @param logger The logger to be used.
	 */
	public static void setLogger(final MMLogger logger) {
		MekHQ.logger = logger;
	}

	/**
	 * @return The logger that will handle log file output.  Will return the
	 * {@link DefaultMmLogger} if a different logger has not been set.
	 */
	public static MMLogger getLogger() {
		if (null == logger) {
			logger = DefaultMmLogger.getInstance();
		}
		return logger;
	}

	public static MekHqPreferences getPreferences() {
	    if (null == preferences) {
	        preferences = new MekHqPreferences();
        }

	    return preferences;
    }

    public static void setWindow(Frame window) {
	    MekHQ.window = window;
    }

    public static ObservableString getSelectedTheme() {
	    return selectedTheme;
    }

	public static ObservableString getPersonnelDirectory() {
	    return personnelDirectory;
    }

    public static ObservableString getCampaignOptionsDirectory() {
        return campaignOptionsDirectory;
    }

    public static ObservableString getPartsDirectory() {
        return partsDirectory;
    }

    public static ObservableString getPlanetsDirectory() {
        return planetsDirectory;
    }

    public static ObservableString getStarMapsDirectory() {
        return starMapsDirectory;
    }

    public static ObservableString getUnitsDirectory() {
        return unitsDirectory;
    }

    public static ObservableString getCampaignsDirectory() {
	    return campaignsDirectory;
    }

    public static ObservableString getScenarioTemplatesDirectory() {
        return scenarioTemplatesDirectory;
    }

    public static ObservableString getFinancesDirectory() {
	    return financesDirectory;
    }

	/**
	 * Designed to centralize output and logging.
	 * Purely a pass-through to the version with a log level.
	 * Default to log level 3.
	 *
	 * @param msg The message you want to log.
	 * @deprecated Use {@link #getLogger()} instead.
	 */
	@Deprecated
	public static void logMessage(String msg) {
		getLogger().log(MekHQ.class, "unknown", LogLevel.INFO, msg);
	}

	/**
	 * Designed to centralize output and logging.
	 *
	 * @param msg The message you want to log.
	 * @param logLevel The log level of the message.
	 * @deprecated Use {@link #getLogger()} instead.
	 */
	@Deprecated
	public static void logMessage(String msg, int logLevel) {
		getLogger().log(MekHQ.class,
						"unknown",
						verbosityToLogLevel(logLevel),
						msg);
	}

	/**
	 * @deprecated Use {@link #getLogger()} instead.
	 */
	@Deprecated
	public static void logError(String err) {
		getLogger().log(MekHQ.class, "unknown", LogLevel.ERROR, err);
	}

	/**
	 * @deprecated Use {@link #getLogger()} instead.
	 */
	@Deprecated
	public static void logError(Exception ex) {
		getLogger().log(MekHQ.class,
						"unknown," +
						LogLevel.ERROR,
						ex);
	}

	protected static MekHQ getInstance() {
		return new MekHQ();
	}

	private MekHQ() {
	    this.autosaveService = new AutosaveService();
    }

    /**
     * At startup create and show the main frame of the application.
     */
    protected void startup() {
        UIManager.installLookAndFeel("Flat Light", "com.formdev.flatlaf.FlatLightLaf");
        UIManager.installLookAndFeel("Flat IntelliJ", "com.formdev.flatlaf.FlatIntelliJLaf");
        UIManager.installLookAndFeel("Flat Dark", "com.formdev.flatlaf.FlatDarkLaf");
        UIManager.installLookAndFeel("Flat Darcula", "com.formdev.flatlaf.FlatDarculaLaf");

        showInfo();

        //Setup user preferences
        getPreferences().loadFromFile(PREFERENCES_FILE);
        setUserPreferences();

    	initEventHandlers();
        //create a start up frame and display it
        StartUpGUI sud = new StartUpGUI(this);
        sud.setVisible(true);
    }

    private void setUserPreferences() {
        PreferencesNode preferences = getPreferences().forClass(MekHQ.class);

        selectedTheme = new ObservableString("selectedTheme", UIManager.getLookAndFeel().getClass().getName());
        selectedTheme.addPropertyChangeListener(new MekHqPropertyChangedListener());
        preferences.manage(new StringPreference(selectedTheme));

        personnelDirectory = new ObservableString("personnelDirectory", ".");
        preferences.manage(new StringPreference(personnelDirectory));

        campaignOptionsDirectory = new ObservableString("campaignOptionsDirectory", ".");
        preferences.manage(new StringPreference(campaignOptionsDirectory));

        partsDirectory = new ObservableString("partsDirectory", ".");
        preferences.manage(new StringPreference(partsDirectory));

        planetsDirectory = new ObservableString("planetsDirectory", ".");
        preferences.manage(new StringPreference(planetsDirectory));

        starMapsDirectory = new ObservableString("starMapsDirectory", ".");
        preferences.manage(new StringPreference(starMapsDirectory));

        unitsDirectory = new ObservableString("unitsDirectory", ".");
        preferences.manage(new StringPreference(unitsDirectory));

        campaignsDirectory = new ObservableString("campaignsDirectory", "./campaigns");
        preferences.manage(new StringPreference(campaignsDirectory));

        scenarioTemplatesDirectory = new ObservableString("scenarioTemplatesDirectory", ".");
        preferences.manage(new StringPreference(scenarioTemplatesDirectory));

        financesDirectory = new ObservableString("financesDirectory", ".");
        preferences.manage(new StringPreference(financesDirectory));
    }

    public void exit() {
    	if(JOptionPane.showConfirmDialog(null,
                "Do you really want to quit MekHQ?",
                "Quit?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) ==
                JOptionPane.YES_OPTION) {
    		if(null != campaigngui) {
        		campaigngui.getFrame().dispose();
        	}
    		getPreferences().saveToFile(PREFERENCES_FILE);
        	System.exit(0);
    	}
    }

    public void showNewView() {
    	campaigngui = new CampaignGUI(this);
    	campaigngui.showOverviewTab(getCampaign().isOverviewLoadingValue());
    }

    /**
     * Main method launching the application.
     */
    public static void main(String[] args) {
    	System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name","MekHQ");

        // We need to reset both the MekHQ and MegaMek log files for now, as we route output to them
        // both
        String logFileNameMHQ = PreferenceManager.getClientPreferences().getLogDirectory()
                + File.separator + DEFAULT_LOG_FILE_NAME;
        String logFileNameMM = PreferenceManager.getClientPreferences().getLogDirectory()
                + File.separator + MegaMek.DEFAULT_LOG_FILE_NAME;
        getLogger().resetLogFile(logFileNameMHQ);
        getLogger().resetLogFile(logFileNameMM);
        // redirect output to log file
        redirectOutput(logFileNameMHQ); // Deprecated call required for MegaMek usage

        SwingUtilities.invokeLater(() -> MekHQ.getInstance().startup());
    }

    private void showInfo() {
        final String METHOD_NAME = "showInfo";
        final long TIMESTAMP = new File(PreferenceManager
                .getClientPreferences().getLogDirectory()
                + File.separator
                + "timestamp").lastModified();
        // echo some useful stuff
        ResourceBundle resourceMap = ResourceBundle.getBundle("mekhq.resources.MekHQ", new EncodeControl()); //$NON-NLS-1$

        StringBuilder msg = new StringBuilder();
        msg.append("\t").append(resourceMap.getString("Application.name")) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" ").append(resourceMap.getString("Application.version")); //$NON-NLS-1$ //$NON-NLS-2$
        if (TIMESTAMP > 0) {
            msg.append("\n\tCompiled on ").append(Instant.ofEpochMilli(TIMESTAMP)); //$NON-NLS-1$
        }
        msg.append("\n\tToday is ").append(LocalDate.now()); //$NON-NLS-1$
        msg.append("\n\tJava vendor ").append(System.getProperty("java.vendor")); //$NON-NLS-1$ //$NON-NLS-2$
        msg.append("\n\tJava version ").append(System.getProperty("java.version")); //$NON-NLS-1$ //$NON-NLS-2$
        msg.append("\n\tPlatform ") //$NON-NLS-1$
               .append(System.getProperty("os.name")) //$NON-NLS-1$
               .append(" ") //$NON-NLS-1$
               .append(System.getProperty("os.version")) //$NON-NLS-1$
               .append(" (") //$NON-NLS-1$
               .append(System.getProperty("os.arch")) //$NON-NLS-1$
               .append(")"); //$NON-NLS-1$
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024;
        msg.append("\n\tTotal memory available to MegaMek: ")
            .append(NumberFormat.getInstance().format(maxMemory)).append(" kB"); //$NON-NLS-1$ //$NON-NLS-2$
        getLogger().log(getClass(), METHOD_NAME, LogLevel.INFO, msg.toString());
    }

    /**
     * This function redirects the standard error and output streams to the
     * given File name.
     */
    @Deprecated // March 12th, 2020. This is no longer used by MekHQ, but is required to hide MegaMek's
    // output to the console for dev builds
    private static void redirectOutput(String logFilename) {
        try {
            System.out.println("Redirecting output to mekhqlog.txt"); //$NON-NLS-1$
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdir();
            }

            // Note: these are not closed on purpose
            OutputStream os = new FileOutputStream(logFilename, true);
            BufferedOutputStream bos = new BufferedOutputStream(os, 64);
			PrintStream ps = new PrintStream(bos);
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception e) {
            MekHQ.getLogger().error(MekHQ.class, "redirectOutput",
                    "Unable to redirect output to mekhqlog.txt", e);
        }
    }

    public Server getMyServer() {
    	return myServer;
    }

    public Campaign getCampaign() {
    	return campaignController.getLocalCampaign();
    }

    public void setCampaign(Campaign c) {
        campaignController = new CampaignController(c);
    }

    public CampaignController getCampaignController() {
        return campaignController;
    }

    /**
     * @return the campaigngui
     */
    public CampaignGUI getCampaigngui() {
        return campaigngui;
    }

    /**
     * @param campaigngui the campaigngui to set
     */
    public void setCampaigngui(CampaignGUI campaigngui) {
        this.campaigngui = campaigngui;
    }

    public void joinGame(Scenario scenario, ArrayList<Unit> meks) {
		LaunchGameDialog lgd = new LaunchGameDialog(campaigngui.getFrame(), false, getCampaign());
		lgd.setVisible(true);

		if(lgd.cancelled) {
		    return;
		}

    	try {
    		client = new Client(lgd.playerName, lgd.serverAddr, lgd.port);
        } catch (Exception ex) {
        	MekHQ.logMessage("Failed to connect to server properly");
			MekHQ.logError(ex);
            return;
        }

    	client.getGame().addGameListener(this);
        currentScenario = scenario;

        //Start the game thread
        gameThread = new GameThread(lgd.playerName, client, this, meks, false);
        gameThread.start();
    }

    public void startHost(Scenario scenario, boolean loadSavegame, ArrayList<Unit> meks) {

        int port;
        String playerName;
        {
            LaunchGameDialog lgd = new LaunchGameDialog(campaigngui.getFrame(), true, getCampaign());
            lgd.setVisible(true);
            if (lgd.cancelled) {
                stopHost();
                return;
            } else {
                this.autosaveService.requestBeforeMissionAutosave(getCampaign());
                port = lgd.port;
                playerName = lgd.playerName;
            }
            lgd.dispose(); // Force cleanup of the current modal, since we are
                           // (possibly) about to display a new one and MacOS
                           // seems to struggle with that.
                           // (see https://github.com/MegaMek/mekhq/issues/953)
        }

        try {
            myServer = new Server("", port);
            if (loadSavegame) {
                FileDialog f = new FileDialog(campaigngui.getFrame(), "Load Savegame");
                f.setDirectory(System.getProperty("user.dir") + "/savegames"); //$NON-NLS-1$ //$NON-NLS-2$
                f.setVisible(true);
                if (null != f.getFile()) {
                    myServer.loadGame(new File(f.getDirectory(), f.getFile()));
                } else {
                    stopHost();
                    return; // exceptions as flow control? no thanks.
                }
            }
        } catch (FileNotFoundException ex) {
            // The dialog was cancelled or the file not found
            // Return to the UI
            stopHost();
            return;
        } catch (Exception ex) {
            MekHQ.getLogger().error(getClass(), "startHost", "Failed to start up server", ex); //$NON-NLS-1$ //$NON-NLS-2$
            stopHost();
            return;
        }

        client = new Client(playerName, "127.0.0.1", port); //$NON-NLS-1$

        client.getGame().addGameListener(this);
        currentScenario = scenario;

        // Start the game thread
        if (getCampaign().getCampaignOptions().getUseAtB() && scenario instanceof AtBScenario) {
            gameThread = new AtBGameThread(playerName, client, this, meks, (AtBScenario) scenario);
        } else {
            gameThread = new GameThread(playerName, client, this, meks);
        }
        gameThread.start();
    }

    // Stop & send the close game event to the Server
    public synchronized void stopHost() {
       if(null != myServer) {
    	   //myServer.getGame().removeGameListener(this);
    	   myServer.die();
    	   myServer = null;
       }
       currentScenario = null;
    }

	@Override
	public void gameBoardChanged(GameBoardChangeEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameBoardNew(GameBoardNewEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameEnd(GameEndEvent e) {

	}

	@Override
	public void gameEntityChange(GameEntityChangeEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameEntityNew(GameEntityNewEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameEntityNewOffboard(GameEntityNewOffboardEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameEntityRemove(GameEntityRemoveEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameMapQuery(GameMapQueryEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameNewAction(GameNewActionEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gamePhaseChange(GamePhaseChangeEvent e) {
	}

	@Override
	public void gameVictory(GameVictoryEvent gve) {
	    // Prevent double run
		if (gameThread.stopRequested()) {
			return;
		}
		try {
        	boolean control = JOptionPane.showConfirmDialog(campaigngui.getFrame(),
                    "Did your side control the battlefield at the end of the scenario?",
                    "Control of Battlefield?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE) ==
                    JOptionPane.YES_OPTION;
        	ResolveScenarioTracker tracker = new ResolveScenarioTracker(currentScenario, getCampaign(), control);
            tracker.setClient(gameThread.getClient());
            tracker.setEvent(gve);
        	tracker.processGame();
        	ResolveScenarioWizardDialog resolveDialog = new ResolveScenarioWizardDialog(campaigngui.getFrame(), true, tracker);
        	resolveDialog.setVisible(true);
        	if (campaigngui.getCampaign().getCampaignOptions().getUseAtB() &&
                    campaigngui.getCampaign().getMission(currentScenario.getMissionId()) instanceof AtBContract &&
        			campaigngui.getCampaign().getRetirementDefectionTracker().getRetirees().size() > 0) {
        		RetirementDefectionDialog rdd = new RetirementDefectionDialog(campaigngui,
        				(AtBContract)campaigngui.getCampaign().getMission(currentScenario.getMissionId()), false);
        		rdd.setVisible(true);
        		if (!rdd.wasAborted()) {
        			getCampaign().applyRetirement(rdd.totalPayout(), rdd.getUnitAssignments());
        		}
        	}
        	gameThread.requestStop();
            /*Megamek dumps these in the deployment phase to free memory*/
            if (getCampaign().getCampaignOptions().getUseAtB()) {
                megamek.client.RandomUnitGenerator.getInstance();
                megamek.client.RandomNameGenerator.getInstance();
            }
            MekHQ.triggerEvent(new ScenarioResolvedEvent(currentScenario));
            campaigngui.initReport();

        }// end try
        catch (Exception ex) {
            logError(ex);
        }
	}

	@Override
	public void gamePlayerChange(GamePlayerChangeEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gamePlayerChat(GamePlayerChatEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gamePlayerConnected(GamePlayerConnectedEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gamePlayerDisconnected(GamePlayerDisconnectedEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameReport(GameReportEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameSettingsChange(GameSettingsChangeEvent e) {
		// TODO Auto-generated method stub

	}

	@Override
	public void gameTurnChange(GameTurnChangeEvent e) {
		// TODO Auto-generated method stub

	}

	public void gameClientFeedbackRequest(GameCFREvent e) {
		// TODO Auto-generated method stub

	}

	public IconPackage getIconPackage() {
	    return iconPackage;
	}

    /**
     * Helper function that calculates the maximum screen width available locally.
     * @return Maximum screen width.
     */
    public double calculateMaxScreenWidth() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] gs = ge.getScreenDevices();
        double maxWidth = 0;
        for (GraphicsDevice g : gs) {
            Rectangle b = g.getDefaultConfiguration().getBounds();
            if (b.getWidth() > maxWidth) {   // Update the max size found on this monitor
                maxWidth = b.getWidth();
            }
        }

        return maxWidth;
    }

	/*
	 * Access methods for event bus.
	 */
	static public void registerHandler(Object handler) {
	    EVENT_BUS.register(handler);
	}

	static public boolean triggerEvent(MMEvent event) {
	    return EVENT_BUS.trigger(event);
	}

	static public void unregisterHandler(Object handler) {
	    EVENT_BUS.unregister(handler);
	}

	// TODO: This needs to be way more flexible, but it will do for now.
	private void initEventHandlers() {
	    EVENT_BUS.register(new XPHandler());
	}

    private static void setLookAndFeel(String themeName) {
	    final String METHOD_NAME = "setLookAndFeel";
        Runnable runnable = () -> {
            try {
                UIManager.setLookAndFeel(themeName);
                if (System.getProperty("os.name", "").startsWith("Mac OS X")) {
                    // Ensure OSX key bindings are used for copy, paste etc
                    addOSXKeyStrokes((InputMap) UIManager.get("EditorPane.focusInputMap"));
                    addOSXKeyStrokes((InputMap) UIManager.get("FormattedTextField.focusInputMap"));
                    addOSXKeyStrokes((InputMap) UIManager.get("TextField.focusInputMap"));
                    addOSXKeyStrokes((InputMap) UIManager.get("TextPane.focusInputMap"));
                    addOSXKeyStrokes((InputMap) UIManager.get("TextArea.focusInputMap"));
                  }
                for (Frame frame : Frame.getFrames()) {
                    SwingUtilities.updateComponentTreeUI(frame);
                }
                for (Window window : Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(window);
                }
            } catch (ClassNotFoundException |
                    InstantiationException |
                    IllegalAccessException |
                    UnsupportedLookAndFeelException e) {
                MekHQ.getLogger().error(
                        MekHQ.class,
                        METHOD_NAME,
                        e);
            }
        };
        SwingUtilities.invokeLater(runnable);
    }

    private static class MekHqPropertyChangedListener implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getSource() == selectedTheme) {
                setLookAndFeel((String)evt.getNewValue());
            }
        }
    }

    private static void addOSXKeyStrokes(InputMap inputMap) {
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK), DefaultEditorKit.copyAction);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.META_DOWN_MASK), DefaultEditorKit.cutAction);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), DefaultEditorKit.pasteAction);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.META_DOWN_MASK), DefaultEditorKit.selectAllAction);
    }
}
