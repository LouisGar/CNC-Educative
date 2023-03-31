/*
 Copyright (C) 2015  Nikos Siatras

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 SourceRabbit GCode Sender is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sourcerabbit.gcode.sender.UI;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.rangepolicies.RangePolicyFixedViewport;
import info.monitorenter.gui.chart.traces.Trace2DSimple;
import info.monitorenter.util.Range;
import java.awt.Color;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Queue;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.ConnectionHelper;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.GCodeExecutionEvents.GCodeExecutionEvent;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.GCodeExecutionEvents.IGCodeExecutionEventListener;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.SerialConnectionEvents.SerialConnectionEvent;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.SerialConnectionEvents.ISerialConnectionEventListener;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.GCodeCycleEvents.GCodeCycleEvent;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.GCodeCycleEvents.IGCodeCycleListener;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.MachineStatusEvents.IMachineStatusEventListener;
import sourcerabbit.gcode.sender.Core.CNCController.Connection.Events.MachineStatusEvents.MachineStatusEvent;
import sourcerabbit.gcode.sender.Core.CNCController.GCode.GCodeCommand;
import sourcerabbit.gcode.sender.Core.CNCController.GRBL.GRBLActiveStates;
import sourcerabbit.gcode.sender.Core.CNCController.GRBL.GRBLCommands;
import sourcerabbit.gcode.sender.Core.Threading.ManualResetEvent;
import sourcerabbit.gcode.sender.Core.CNCController.Position.Position2D;
import sourcerabbit.gcode.sender.Core.CNCController.Position.Position4D;
import sourcerabbit.gcode.sender.Core.CNCController.Processes.Process_Jogging;
import sourcerabbit.gcode.sender.Core.CNCController.Processes.Process_ZeroWorkPosition;
import sourcerabbit.gcode.sender.Core.Machine.MachineInformation;
import sourcerabbit.gcode.sender.Core.Settings.SemiAutoToolChangeSettings;
import sourcerabbit.gcode.sender.Core.Settings.SettingsManager;
import sourcerabbit.gcode.sender.Core.Units.EUnits;
import sourcerabbit.gcode.sender.UI.Machine.frmToolChangeSettings;
import sourcerabbit.gcode.sender.UI.Tools.frmHoleCenterFinder;
import sourcerabbit.gcode.sender.UI.Tools.frmSetWorkPosition;
import sourcerabbit.gcode.sender.UI.Tools.frmZAxisTouchProbe;
import sourcerabbit.gcode.sender.UI.UITools.UITools;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;



/**
 * @author Louis Garez
 */
public class frmControl extends javax.swing.JFrame
{

    public static frmControl fInstance;
    private ManualResetEvent fMachineStatusThreadWait;
    private boolean fKeepMachineStatusThreadRunning;
    private boolean fMachineIsCyclingGCode = false;
    private Thread fMachineStatusThread;
    private EUnits fJoggingUnits = EUnits.Metric;
    private static final Object fAddRemoveLogTableLines = new Object();

    private final DateFormat fDateFormat = new SimpleDateFormat("HH:mm:ss");

    // Macros
    private final ArrayList<JTextField> fMacroTexts = new ArrayList<>();
    private final ArrayList<JButton> fMacroButtons = new ArrayList<>();

    // Connection
    private boolean fSerialConnectionIsOn = false;

    public frmControl()
    {
        fInstance = this;
        initComponents();

        // Fix decoration for FlatLaf
        dispose();
        setUndecorated(true);
        getRootPane().setWindowDecorationStyle(JRootPane.FRAME);
        setVisible(true);
        JFrame.setDefaultLookAndFeelDecorated(false);

        // Set form in middle of screen
        Position2D pos = UITools.getPositionForFormToOpenInMiddleOfScreen(this.getSize().width, this.getSize().height);
        this.setLocation((int) pos.getX(), (int) pos.getY());

        InitEvents();
        InitUIThreads();

        this.setTitle("SourceRabbit GCode Sender (Version " + SettingsManager.getAppVersion() + ")");
        this.setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource("Images/SourceRabbitIcon.png")));

        this.jCheckBoxEnableGCodeLog.setSelected(SettingsManager.getIsGCodeLogEnabled());
        this.jCheckBoxEnableKeyboardJogging.setSelected(SettingsManager.getIsKeyboardJoggingEnabled());

        InitMacroButtons();

        InitKeyListener();

        // Fix jSpinnerStep to work with system decimal point
        jSpinnerStep.setEditor(new JSpinner.NumberEditor(jSpinnerStep, "##.###"));
        SpinnerNumberModel spinnerModel = (SpinnerNumberModel) jSpinnerStep.getModel();
        spinnerModel.setStepSize(.001);
        spinnerModel.setValue(1.000);
        spinnerModel.setMinimum(0.001);
        jSpinnerStep.setModel(spinnerModel);
        UITools.FixSpinnerToWorkWithSystemDecimalPoint(jSpinnerStep);      
    }

    private void InitMacroButtons()
    {
        ArrayList<String> savedMacros = SettingsManager.getMacros();

        int topOffset = 50;
        for (int i = 0; i < 7; i++)
        {
            final JButton button = new JButton();
            int id = i + 1;
            button.setText("C" + String.valueOf(id));
            button.setSize(50, 30);
            button.setLocation(10, topOffset + (i * 35));
            fMacroButtons.add(button);

            final JTextField textField = new JTextField();
            textField.setText(savedMacros.get(i));
            textField.setSize(300, 30);
            textField.setLocation(80, topOffset + (i * 35));
            fMacroTexts.add(textField);

            textField.addKeyListener(new KeyAdapter()
            {
                @Override
                public void keyReleased(KeyEvent e)
                {
                    try
                    {
                        ArrayList<String> macroCommands = new ArrayList<>();
                        for (JTextField text : fMacroTexts)
                        {
                            macroCommands.add(text.getText());
                        }
                        SettingsManager.setMacros(macroCommands);
                    }
                    catch (Exception ex)
                    {

                    }
                }
            });

            button.addActionListener(new java.awt.event.ActionListener()
            {
                @Override
                public void actionPerformed(ActionEvent e)
                {
                    final String commandStr = textField.getText().replaceAll("(\\r\\n|\\n\\r|\\r|\\n)", "");

                    // Get commands
                    String commands[] = commandStr.split(";");
                    for (String commandString : commands)
                    {
                        GCodeCommand command = new GCodeCommand(commandString);
                        String response = ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommandAndGetResponse(command);
                        WriteToConsole(commandString + "\nResponse:" + response + "\n");
                        
                    }
                }
            });

            // Add button and textfield
            jPanelMacros.add(button);
            jPanelMacros.add(textField);
        }
    }

    public void WriteToConsole(String output)
    {
        String dateTime = (fDateFormat.format(new Date(System.currentTimeMillis())));
        jTextAreaConsole.append(dateTime + " - " + output + "\n");
        jTextAreaConsole.setCaretPosition(jTextAreaConsole.getDocument().getLength());
    }

    private void InitEvents()
    {
        // Machine status events
        ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMachineStatusEventsManager().AddListener(new IMachineStatusEventListener()
        {
            @Override
            public void MachineStatusChanged(MachineStatusEvent evt)
            {

            }

            @Override
            public void MachineStatusReceived(MachineStatusEvent evt)
            {
                final int activeState = evt.getMachineStatus();
                boolean enableMachineControlButtons = false;

                switch (activeState)
                {
                    case GRBLActiveStates.IDLE:
                        jLabelActiveState.setForeground(new Color(0, 153, 51));
                        jLabelActiveState.setText("Idle");
                        enableMachineControlButtons = true;
                        break;

                    case GRBLActiveStates.RUN:
                        jLabelActiveState.setForeground(Color.WHITE);
                        jLabelActiveState.setText("Run");
                        enableMachineControlButtons = false;

                        //////////////////////////////////////////////////////////////////////////////////////////////
                        // Fix the jButtonGCodePause !!!!
                        jButtonGCodePause.setEnabled(true);
                        jButtonGCodePause.setText("Pause");
                        //////////////////////////////////////////////////////////////////////////////////////////////
                        break;

                    case GRBLActiveStates.HOLD:
                        jLabelActiveState.setForeground(Color.red);
                        jLabelActiveState.setText("Hold");
                        jTextFieldCommand.setEnabled(true);
                        enableMachineControlButtons = false;

                        //////////////////////////////////////////////////////////////////////////////////////////////
                        // Fix the jButtonGCodePause !!!!
                        jButtonGCodePause.setEnabled(true);
                        jButtonGCodePause.setText("Resume");
                        //////////////////////////////////////////////////////////////////////////////////////////////
                        break;

                    case GRBLActiveStates.HOME:
                        jLabelActiveState.setForeground(Color.WHITE);
                        jLabelActiveState.setText("Homing...");
                        enableMachineControlButtons = false;
                        break;

                    case GRBLActiveStates.CHECK:
                        jLabelActiveState.setForeground(new Color(0, 153, 51));
                        jLabelActiveState.setText("Check");
                        enableMachineControlButtons = false;
                        break;

                    case GRBLActiveStates.ALARM:
                        jLabelActiveState.setForeground(Color.red);
                        jLabelActiveState.setText("Alarm!");
                        jButtonKillAlarm.setText("Kill Alarm");
                        enableMachineControlButtons = false;
                        break;

                    case GRBLActiveStates.MACHINE_IS_LOCKED:
                        jLabelActiveState.setForeground(Color.red);
                        jLabelActiveState.setText("Locked!");
                        jButtonKillAlarm.setText("Unlock");
                        enableMachineControlButtons = false;
                        break;

                    case GRBLActiveStates.RESET_TO_CONTINUE:
                        jLabelActiveState.setForeground(Color.red);
                        jLabelActiveState.setText("Error: Soft Reset...");
                        enableMachineControlButtons = false;
                        try
                        {
                            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendDataImmediately_WithoutMessageCollector(GRBLCommands.COMMAND_SOFT_RESET);
                        }
                        catch (Exception ex)
                        {

                        }
                        break;

                    case GRBLActiveStates.JOG:
                        jLabelActiveState.setForeground(new Color(0, 153, 51));
                        jLabelActiveState.setText("Jogging");
                        enableMachineControlButtons = true;
                        break;
                }

                // Show or Hide jButtonKillAlarm
                jButtonKillAlarm.setVisible(activeState == GRBLActiveStates.ALARM || activeState == GRBLActiveStates.MACHINE_IS_LOCKED);

                if (activeState == GRBLActiveStates.RESET_TO_CONTINUE || activeState == GRBLActiveStates.ALARM || activeState == GRBLActiveStates.HOME)
                {
                    // Machine is in Alarm State or Needs Reset or is Homing
                    // In this state disable all control components
                    SetMachineControlsEnabled(false);
                    // FIX for Machine Menu Items when machine needs reset
                    jMenuItemToolChangeSettings.setEnabled(false);
                    jMenuItemStartHomingSequence.setEnabled(false);
                }
                else
                {
                    // Enable or disable machine control buttons
                    // If machine is cycling gcode then disable all control buttons
                    SetMachineControlsEnabled((fMachineIsCyclingGCode == true) ? false : enableMachineControlButtons);

                    // If the machine is changing tool then change the Active state text
                    // and foreground color
                    if (ConnectionHelper.AUTO_TOOL_CHANGE_OPERATION_IS_ACTIVE)
                    {
                        jLabelActiveState.setText("Changing Tool...");
                        jLabelActiveState.setForeground(Color.WHITE);
                    }

                    ////////////////////////////////////////////////////////////////////////////////////////////
                    // Enable or Disable appropriate components when machine is cycling GCode
                    ////////////////////////////////////////////////////////////////////////////////////////////
                    EnableOrDisableComponentsWhenMachineIsCyclingGCode(fMachineIsCyclingGCode);
                    ////////////////////////////////////////////////////////////////////////////////////////////
                }
            }
        });

        // Serial Connection Events
        ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getSerialConnectionEventManager().AddListener(new ISerialConnectionEventListener()
        {
            @Override
            public void ConnectionEstablished(SerialConnectionEvent evt)
            {
                WriteToConsole("Connection Established!");
                fSerialConnectionIsOn = true;
                fMachineIsCyclingGCode = false;
                jButtonConnectDisconnect1.setText("Disconnect");
                jButtonConnectDisconnect1.setEnabled(true);
                jButtonSoftReset.setEnabled(true);

                // Enable Machine Control Components
                SetMachineControlsEnabled(true);

                jMenuItemGRBLSettings.setEnabled(true);
            }

            @Override
            public void ConnectionClosed(SerialConnectionEvent evt)
            {
                WriteToConsole("Connection Closed!");
                fSerialConnectionIsOn = false;
                fMachineIsCyclingGCode = false;
                ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().CancelSendingGCode(false);

                jButtonConnectDisconnect1.setText("Connect");
                jButtonConnectDisconnect1.setEnabled(true);
                jButtonSoftReset.setEnabled(false);

                jLabelActiveState.setForeground(Color.red);
                jLabelActiveState.setText("Disconnected");

                // Disable Machine Control Components
                SetMachineControlsEnabled(false);

                jMenuItemGRBLSettings.setEnabled(false);
            }

            @Override
            public void DataReceivedFromSerialConnection(SerialConnectionEvent evt)
            {
                String data = (String) evt.getSource();
                if (!data.startsWith("$") && !data.contains("="))
                {
                    // Write all incoming data except the machine settings 
                    // Example $1=0
                    WriteToConsole(data);
                }
            }
        });

        // Gcode Cycle Events
        ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getCycleEventManager().AddListener(new IGCodeCycleListener()
        {
            @Override
            public void GCodeCycleStarted(GCodeCycleEvent evt)
            {
                WriteToConsole("Cycle Started!");
                fMachineIsCyclingGCode = true;

                jProgressBarGCodeProgress.setValue(0);
                jProgressBarGCodeProgress.setMaximum(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsInFile());
            }

            @Override
            public void GCodeCycleFinished(GCodeCycleEvent evt)
            {
                fMachineIsCyclingGCode = false;
                WriteToConsole("Cycle Finished!");
                JOptionPane.showMessageDialog(fInstance, evt.getSource().toString(), "Finished", JOptionPane.INFORMATION_MESSAGE);
            }

            @Override
            public void GCodeCycleCanceled(GCodeCycleEvent evt)
            {
                WriteToConsole("Cycle Canceled");
                fMachineIsCyclingGCode = false;
                jProgressBarGCodeProgress.setValue(0);
                jProgressBarGCodeProgress.setMaximum(0);
            }

            @Override
            public void GCodeCyclePaused(GCodeCycleEvent evt)
            {
                WriteToConsole("Cycle Paused");
                fMachineIsCyclingGCode = true;
            }

            @Override
            public void GCodeCycleResumed(GCodeCycleEvent evt)
            {
                WriteToConsole("Cycle Resumed");
                fMachineIsCyclingGCode = true;
            }
        });

        // GCode Execution Events
        ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getGCodeExecutionEventsManager().AddListener(new IGCodeExecutionEventListener()
        {
            @Override
            public void GCodeCommandSentToController(GCodeExecutionEvent evt)
            {
                try
                {
                    if (jCheckBoxEnableGCodeLog.isSelected() || !evt.getCommand().getError().equals(""))
                    {
                        synchronized (fAddRemoveLogTableLines)
                        {
                            DefaultTableModel model = (DefaultTableModel) jTableGCodeLog.getModel();

                            if (evt.getCommand().getLineNumber() == -1)
                            {
                                // GCode Has No Line Number
                                model.addRow(new Object[]
                                {
                                    "", evt.getCommand().getCommand(), true, false
                                });
                            }
                            else
                            {
                                // GCode Has Line Number
                                model.addRow(new Object[]
                                {
                                    String.valueOf(evt.getCommand().getLineNumber()), evt.getCommand().getCommand(), true, false
                                });
                            }

                        }
                    }
                }
                catch (Exception ex)
                {
                }
            }

            @Override
            public void GCodeExecutedSuccessfully(GCodeExecutionEvent evt)
            {
                try
                {
                    if (jCheckBoxEnableGCodeLog.isSelected() || !evt.getCommand().getError().equals(""))
                    {
                        synchronized (fAddRemoveLogTableLines)
                        {
                            DefaultTableModel model = (DefaultTableModel) jTableGCodeLog.getModel();
                            if (!evt.getCommand().getCommand().equals(""))
                            {
                                int lastRow = model.getRowCount() - 1;
                                model.setValueAt(true, lastRow, 3);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                }
            }

            @Override
            public void GCodeExecutedWithError(GCodeExecutionEvent evt)
            {
                try
                {
                    synchronized (fAddRemoveLogTableLines)
                    {
                        DefaultTableModel model = (DefaultTableModel) jTableGCodeLog.getModel();
                        if (!evt.getCommand().equals(""))
                        {
                            int lastRow = model.getRowCount() - 1;
                            model.setValueAt(true, lastRow, 3);
                            model.setValueAt(evt.getCommand().getError(), lastRow, 4);
                        }
                    }
                }
                catch (Exception ex)
                {
                }
            }
        }
        );
    }

    private void SetMachineControlsEnabled(boolean state)
    {
        jTextFieldCommand.setEnabled(state);
        jButtonResetWorkPosition.setEnabled(state);
        jButtonReturnToZero.setEnabled(state);
        jButtonGCodeSend.setEnabled(state);

        // Enable or Disable Machine Control Components
        for (Component c : jPanelMachineControl.getComponents())
        {
            c.setEnabled(state);
        }

        // Enable or Disable jog buttons
        for (Component c : jPanelJogButtons.getComponents())
        {
            c.setEnabled(state);
        }

        // Enable or Disable Macros
        try
        {
            // Enable or disable all components in jPanelMacros
            Component[] components = jPanelMacros.getComponents();
            for (Component component : components)
            {
                component.setEnabled(state);
            }
        }
        catch (Exception ex)
        {

        }
    }

    /**
     * Enables or disables the appropriate UI components when the machine is
     * cycling G-Code
     *
     * @param isGcodeCycling
     */
    private void EnableOrDisableComponentsWhenMachineIsCyclingGCode(boolean isGcodeCycling)
    {
        jButtonGCodePause.setEnabled(isGcodeCycling);
        jButtonGCodeCancel.setEnabled(isGcodeCycling);

        jButtonConnectDisconnect1.setEnabled(!isGcodeCycling);
        jButtonGCodeBrowse.setEnabled(!isGcodeCycling);
        jButtonGCodeSend.setEnabled(!isGcodeCycling);
        jButtonResetWorkPosition.setEnabled(!isGcodeCycling);
        jTextFieldGCodeFile.setEnabled(!isGcodeCycling);

        jTextAreaConsole.setEnabled(!isGcodeCycling);
        jMenuItemGRBLSettings.setEnabled(!isGcodeCycling);
        jMenuItemToolChangeSettings.setEnabled(!isGcodeCycling);
        jMenuItemStartHomingSequence.setEnabled(!isGcodeCycling);

        if (ConnectionHelper.AUTO_TOOL_CHANGE_OPERATION_IS_ACTIVE)
        {
            jButtonGCodeCancel.setEnabled(false);
        }
    }

    private void InitUIThreads()
    {
        fMachineStatusThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (fKeepMachineStatusThreadRunning)
                {
                    fMachineStatusThreadWait.Reset();

                    try
                    {
                        // Update Work position                             
                        jLabelWorkPositionX.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getX()));
                        jLabelWorkPositionY.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getY()));
                        jLabelWorkPositionZ.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getZ()));

                        // Update Machine Position
                        jLabelMachinePositionX.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMachinePosition().getX()));
                        jLabelMachinePositionY.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMachinePosition().getY()));
                        jLabelMachinePositionZ.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMachinePosition().getZ()));

                        // Update real time Feed Rate
                        jLabelRealTimeFeedRate.setText(String.valueOf(MachineInformation.LiveFeedRate().get()) + " mm/min");
                        jLabelRealTimeSpindleRPM.setText(String.valueOf(MachineInformation.LiveSpindleRPM().get()));

                        jLabelLastStatusUpdate.setText((System.currentTimeMillis() - ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getLastMachineStatusReceivedTimestamp()) + " ms ago");

                        jLabelMachineHomePosition.setText(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.fXHomePosition + ", " + ConnectionHelper.ACTIVE_CONNECTION_HANDLER.fYHomePosition + ", " + ConnectionHelper.ACTIVE_CONNECTION_HANDLER.fZHomePosition);

                        // Update bytes per second
                        String bytesText = "Connection (Bytes In/Out: " + ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getBytesInPerSec() + " / " + ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getBytesOutPerSec() + ")";
                        TitledBorder border = (TitledBorder) jPanelConnection.getBorder();
                        border.setTitle(bytesText);
                        jPanelConnection.repaint();

                        // Semi Auto Tool Change Status
                        if (SemiAutoToolChangeSettings.isSemiAutoToolChangeEnabled())
                        {
                            jLabelSemiAutoToolChangeStatus.setText("On");
                            jLabelSemiAutoToolChangeStatus.setForeground(Color.WHITE);
                        }
                        else
                        {
                            jLabelSemiAutoToolChangeStatus.setText("Off");
                            jLabelSemiAutoToolChangeStatus.setForeground(Color.red);
                        }

                        // Update remaining rows & rows sent
                        jLabelSentRows.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsSent()));
                        jLabelRemainingRows.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsRemaining()));

                        jProgressBarGCodeProgress.setValue(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsSent());

                        // Time elapsed
                        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeCycleStartedTimestamp() > 0)
                        {
                            long millis = System.currentTimeMillis() - ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeCycleStartedTimestamp();
                            long second = (millis / 1000) % 60;
                            long minute = (millis / (1000 * 60)) % 60;
                            long hour = (millis / (1000 * 60 * 60)) % 24;

                            String time = String.format("%02d:%02d:%02d", hour, minute, second);
                            jLabelTimeElapsed.setText(time);
                        }
                    }
                    catch (Exception ex)
                    {
                        // DO NOTHING
                        // This exception is here only to protect from UI update failure
                    }

                    fMachineStatusThreadWait.WaitOne(250);
                }
            }
        });
        fMachineStatusThread.setPriority(Thread.MIN_PRIORITY);
        fKeepMachineStatusThreadRunning = true;
        fMachineStatusThreadWait = new ManualResetEvent(false);
        fMachineStatusThread.start();
    }

    // Initialize a new KeyListener to control the jogging via Keyboard
    private void InitKeyListener()
    {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher()
        {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e)
            {
                // Check if a "text" option is focused
                boolean textIsFocused = jTextFieldGCodeFile.hasFocus()
                        || jTextFieldCommand.hasFocus()
                        || (e.getSource() instanceof JFormattedTextField
                        || jTextAreaConsole.hasFocus()
                        || jTableGCodeLog.hasFocus()
                        || fMacroTexts.contains(e.getSource())
                        || fMacroButtons.contains(e.getSource()));

                if (!textIsFocused && jCheckBoxEnableKeyboardJogging.isSelected() && e.getID() == KeyEvent.KEY_PRESSED)
                {
                    boolean jog = false;
                    final String jogAxis;

                    switch (e.getKeyCode())
                    {
                        case KeyEvent.VK_RIGHT:
                        case KeyEvent.VK_KP_RIGHT:
                            jog = true;
                            jogAxis = "X";
                            break;

                        case KeyEvent.VK_LEFT:
                        case KeyEvent.VK_KP_LEFT:
                            jog = true;
                            jogAxis = "X-";
                            break;

                        case KeyEvent.VK_UP:
                        case KeyEvent.VK_KP_UP:
                            jog = true;
                            jogAxis = "Y";
                            break;

                        case KeyEvent.VK_DOWN:
                        case KeyEvent.VK_KP_DOWN:
                            jog = true;
                            jogAxis = "Y-";
                            break;

                        case KeyEvent.VK_PAGE_UP:
                            jog = true;
                            jogAxis = "Z";
                            break;

                        case KeyEvent.VK_PAGE_DOWN:
                            jog = true;
                            jogAxis = "Z-";
                            break;

                        default:
                            jogAxis = "";
                            break;
                    }

                    if (jog)
                    {
                        final double stepValue = (double) jSpinnerStep.getValue();

                        Thread th = new Thread(() ->
                        {
                            Process_Jogging p = new Process_Jogging(null, jogAxis, stepValue, fJoggingUnits);
                            p.Execute();
                            p.Dispose();
                        });
                        th.start();

                        return true;
                    }
                }

                return false;
            }

        });
    }
     public synchronized void getInfo() {
        while (info == null) {
            try {
                wait();
            } catch (InterruptedException e) {
                process_fini=0;
                System.out.print("Error in receive");
                return;
            }
        }
        if("5".equals(info)){
            process_fini=2;
            System.out.print("Process Received");
        }
        else{
            System.out.print("Error in receive since received else"+info);
        }
      
        
    }

    public synchronized void setInfo(String info) {
        this.info = info;
        notify();
    }
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jDialogLevel = new javax.swing.JDialog();
        jPanel13 = new javax.swing.JPanel();
        jLevelCombo = new javax.swing.JComboBox<>();
        jLabel48 = new javax.swing.JLabel();
        jConfirmerBoutton = new javax.swing.JButton();
        jDialog3 = new javax.swing.JDialog();
        jPanel30 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        jButton26 = new javax.swing.JButton();
        jDialog1 = new javax.swing.JDialog();
        jPanelConnection1 = new javax.swing.JPanel();
        jLabelMachineX2 = new javax.swing.JLabel();
        jLabelActiveState1 = new javax.swing.JLabel();
        jButtonConnectDisconnect2 = new javax.swing.JButton();
        jButtonKillAlarm2 = new javax.swing.JButton();
        jButtonKillAlarm3 = new javax.swing.JButton();
        jButtonSoftReset2 = new javax.swing.JButton();
        jLabel8 = new javax.swing.JLabel();
        jLabel18 = new javax.swing.JLabel();
        jSeparator4 = new javax.swing.JSeparator();
        jPanel8 = new javax.swing.JPanel();
        jButtonKillAlarm1 = new javax.swing.JButton();
        jButtonSoftReset1 = new javax.swing.JButton();
        jLabel19 = new javax.swing.JLabel();
        jLabel20 = new javax.swing.JLabel();
        jLabel21 = new javax.swing.JLabel();
        jButton7 = new javax.swing.JButton();
        jButton8 = new javax.swing.JButton();
        jDialog2 = new javax.swing.JDialog();
        jLabel22 = new javax.swing.JLabel();
        jLabel23 = new javax.swing.JLabel();
        jSeparator6 = new javax.swing.JSeparator();
        jSeparator7 = new javax.swing.JSeparator();
        jPanel10 = new javax.swing.JPanel();
        jButtonResetWorkPosition1 = new javax.swing.JButton();
        jPanel11 = new javax.swing.JPanel();
        jLabel27 = new javax.swing.JLabel();
        jLabelWorkPositionZ1 = new javax.swing.JLabel();
        jLabelWorkPositionX1 = new javax.swing.JLabel();
        jLabelWorkPositionY1 = new javax.swing.JLabel();
        jLabelMachinePositionZ1 = new javax.swing.JLabel();
        jLabelMachinePositionX1 = new javax.swing.JLabel();
        jLabelMachinePositionY1 = new javax.swing.JLabel();
        jLabel28 = new javax.swing.JLabel();
        jLabel29 = new javax.swing.JLabel();
        jLabel30 = new javax.swing.JLabel();
        jButton4 = new javax.swing.JButton();
        jButton5 = new javax.swing.JButton();
        jButton6 = new javax.swing.JButton();
        jLabelRowsInFile8 = new javax.swing.JLabel();
        jLabelSemiAutoToolChangeStatus1 = new javax.swing.JLabel();
        jLabel31 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate1 = new javax.swing.JLabel();
        jLabel32 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM1 = new javax.swing.JLabel();
        jLabel33 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate2 = new javax.swing.JLabel();
        jLabel34 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM2 = new javax.swing.JLabel();
        jLabel24 = new javax.swing.JLabel();
        jButtonCancel = new javax.swing.JButton();
        jButtonOk = new javax.swing.JButton();
        jDialogGSender = new javax.swing.JDialog();
        jLabel25 = new javax.swing.JLabel();
        jLabel26 = new javax.swing.JLabel();
        jSeparator8 = new javax.swing.JSeparator();
        jSeparator9 = new javax.swing.JSeparator();
        jButtonCancel1 = new javax.swing.JButton();
        jButtonOk1 = new javax.swing.JButton();
        jPanel9 = new javax.swing.JPanel();
        jLabelRowsInFile6 = new javax.swing.JLabel();
        jLabelRowsInFile14 = new javax.swing.JLabel();
        jLabelRowsInFile15 = new javax.swing.JLabel();
        jLabelRowsInFile16 = new javax.swing.JLabel();
        jLabelSentRows1 = new javax.swing.JLabel();
        jLabelRemainingRows1 = new javax.swing.JLabel();
        jLabelRowsInFile17 = new javax.swing.JLabel();
        jLabelTimeElapsed1 = new javax.swing.JLabel();
        jLabelRowsInFile18 = new javax.swing.JLabel();
        jProgressBarGCodeProgress1 = new javax.swing.JProgressBar();
        jPanelGCodeFile1 = new javax.swing.JPanel();
        jTextFieldGCodeFile1 = new javax.swing.JTextField();
        jLabel88 = new javax.swing.JLabel();
        jButtonGCodeBrowse1 = new javax.swing.JButton();
        jButtonGCodePause1 = new javax.swing.JButton();
        jButtonGCodeSend1 = new javax.swing.JButton();
        jButtonGCodeCancel1 = new javax.swing.JButton();
        jButtonGCodeVisualize1 = new javax.swing.JButton();
        jButtonBrowse2 = new javax.swing.JButton();
        jButtonVisualise2 = new javax.swing.JButton();
        jProgressBarGCodeProgress2 = new javax.swing.JProgressBar();
        jLabelRowsInFile19 = new javax.swing.JLabel();
        jDialogTab1 = new javax.swing.JDialog();
        jLabel44 = new javax.swing.JLabel();
        jLabel45 = new javax.swing.JLabel();
        jSeparator10 = new javax.swing.JSeparator();
        jSeparator11 = new javax.swing.JSeparator();
        jButtonCancel2 = new javax.swing.JButton();
        jButtonOk2 = new javax.swing.JButton();
        jPanel12 = new javax.swing.JPanel();
        jLabel35 = new javax.swing.JLabel();
        jTextFieldCommand1 = new javax.swing.JTextField();
        jScrollPane6 = new javax.swing.JScrollPane();
        jTextAreaConsole1 = new javax.swing.JTextArea();
        jButtonClearConsole1 = new javax.swing.JButton();
        jCheckBoxShowVerboseOutput1 = new javax.swing.JCheckBox();
        jDialogTab2 = new javax.swing.JDialog();
        jLabel55 = new javax.swing.JLabel();
        jLabel56 = new javax.swing.JLabel();
        jSeparator12 = new javax.swing.JSeparator();
        jSeparator13 = new javax.swing.JSeparator();
        jButtonCancel3 = new javax.swing.JButton();
        jButtonOk3 = new javax.swing.JButton();
        jScrollPane3 = new javax.swing.JScrollPane();
        jTableGCodeLog1 = new javax.swing.JTable();
        jButtonClearLog1 = new javax.swing.JButton();
        jCheckBoxEnableGCodeLog1 = new javax.swing.JCheckBox();
        jDialogTab3 = new javax.swing.JDialog();
        jLabel66 = new javax.swing.JLabel();
        jLabel67 = new javax.swing.JLabel();
        jSeparator14 = new javax.swing.JSeparator();
        jSeparator15 = new javax.swing.JSeparator();
        jButtonCancel4 = new javax.swing.JButton();
        jButtonOk4 = new javax.swing.JButton();
        jDialogTab4 = new javax.swing.JDialog();
        jLabel77 = new javax.swing.JLabel();
        jLabel78 = new javax.swing.JLabel();
        jSeparator16 = new javax.swing.JSeparator();
        jSeparator17 = new javax.swing.JSeparator();
        jButtonCancel5 = new javax.swing.JButton();
        jButtonOk5 = new javax.swing.JButton();
        jFrame1 = new javax.swing.JFrame();
        jLabel36 = new javax.swing.JLabel();
        jSeparator2 = new javax.swing.JSeparator();
        jLabel37 = new javax.swing.JLabel();
        jScrollPane7 = new javax.swing.JScrollPane();
        jTable1 = new javax.swing.JTable();
        jButton9 = new javax.swing.JButton();
        jButton10 = new javax.swing.JButton();
        jButton11 = new javax.swing.JButton();
        jDialogMiseEnPlace = new javax.swing.JDialog();
        jPanel14 = new javax.swing.JPanel();
        jPanel15 = new javax.swing.JPanel();
        jLabel60 = new javax.swing.JLabel();
        jLabel38 = new javax.swing.JLabel();
        jButton12 = new javax.swing.JButton();
        jButton13 = new javax.swing.JButton();
        jDialogZeroAxes = new javax.swing.JDialog();
        jPanel24 = new javax.swing.JPanel();
        jPanel25 = new javax.swing.JPanel();
        jPanel31 = new javax.swing.JPanel();
        jPanelMachineControl1 = new javax.swing.JPanel();
        jRadioButtonInches1 = new javax.swing.JRadioButton();
        jRadioButtonMillimeters1 = new javax.swing.JRadioButton();
        jLabel42 = new javax.swing.JLabel();
        jSpinnerStep1 = new javax.swing.JSpinner();
        jPanelJogButtons1 = new javax.swing.JPanel();
        jButtonYMinus1 = new javax.swing.JButton();
        jButtonXMinus1 = new javax.swing.JButton();
        jButtonYPlus1 = new javax.swing.JButton();
        jButtonXPlus1 = new javax.swing.JButton();
        jButtonZPlus1 = new javax.swing.JButton();
        jButtonZMinus1 = new javax.swing.JButton();
        jCheckBoxEnableKeyboardJogging1 = new javax.swing.JCheckBox();
        jLabelRemoveFocus1 = new javax.swing.JLabel();
        jButtonReturnToZero1 = new javax.swing.JButton();
        jSliderStepSize1 = new javax.swing.JSlider();
        jPanelConnection2 = new javax.swing.JPanel();
        jButtonSoftReset3 = new javax.swing.JButton();
        jLabelMachineX3 = new javax.swing.JLabel();
        jButtonKillAlarm4 = new javax.swing.JButton();
        jLabelActiveState2 = new javax.swing.JLabel();
        jButtonConnectDisconnect3 = new javax.swing.JButton();
        jPanelConnection4 = new javax.swing.JPanel();
        jLabel50 = new javax.swing.JLabel();
        jLabel39 = new javax.swing.JLabel();
        jButton14 = new javax.swing.JButton();
        jButton24 = new javax.swing.JButton();
        jButton25 = new javax.swing.JButton();
        jDialogImportGCode = new javax.swing.JDialog();
        jPanel26 = new javax.swing.JPanel();
        jPanel28 = new javax.swing.JPanel();
        jLabel40 = new javax.swing.JLabel();
        jButton27 = new javax.swing.JButton();
        jPanel29 = new javax.swing.JPanel();
        jLabelRowsInFile9 = new javax.swing.JLabel();
        jLabelRowsInFile10 = new javax.swing.JLabel();
        jLabelRowsInFile20 = new javax.swing.JLabel();
        jLabelRowsInFile21 = new javax.swing.JLabel();
        jLabelSentRows2 = new javax.swing.JLabel();
        jLabelRemainingRows2 = new javax.swing.JLabel();
        jPanelGCodeFile2 = new javax.swing.JPanel();
        jButtonGCodeBrowse2 = new javax.swing.JButton();
        jTextFieldGCodeFile2 = new javax.swing.JTextField();
        jLabel41 = new javax.swing.JLabel();
        jButtonGCodePause2 = new javax.swing.JButton();
        jButtonGCodeSend2 = new javax.swing.JButton();
        jButtonGCodeCancel2 = new javax.swing.JButton();
        jButtonGCodeVisualize2 = new javax.swing.JButton();
        jButtonBrowse1 = new javax.swing.JButton();
        jButtonVisualise1 = new javax.swing.JButton();
        jDialogAskTuto = new javax.swing.JDialog();
        jLabel51 = new javax.swing.JLabel();
        jSeparator5 = new javax.swing.JSeparator();
        jLabel52 = new javax.swing.JLabel();
        jSeparator18 = new javax.swing.JSeparator();
        jButton15 = new javax.swing.JButton();
        jButton16 = new javax.swing.JButton();
        JDialogSecuriter = new javax.swing.JDialog();
        jLabel54 = new javax.swing.JLabel();
        jTableGCodeLog2 = new javax.swing.JTable();
        jButtonOk7 = new javax.swing.JButton();
        jButtonCancel7 = new javax.swing.JButton();
        jLabel58 = new javax.swing.JLabel();
        jDialogMachineAxis = new javax.swing.JDialog();
        jLabel53 = new javax.swing.JLabel();
        jLabel57 = new javax.swing.JLabel();
        jSeparator20 = new javax.swing.JSeparator();
        jSeparator21 = new javax.swing.JSeparator();
        jButtonCancel6 = new javax.swing.JButton();
        jButtonOk6 = new javax.swing.JButton();
        jPanelMachineControl2 = new javax.swing.JPanel();
        jRadioButtonInches2 = new javax.swing.JRadioButton();
        jRadioButtonMillimeters2 = new javax.swing.JRadioButton();
        jLabel65 = new javax.swing.JLabel();
        jSpinnerStep2 = new javax.swing.JSpinner();
        jPanelJogButtons2 = new javax.swing.JPanel();
        jButtonYMinus2 = new javax.swing.JButton();
        jButtonXMinus2 = new javax.swing.JButton();
        jButtonYPlus2 = new javax.swing.JButton();
        jButtonXPlus2 = new javax.swing.JButton();
        jButtonZPlus2 = new javax.swing.JButton();
        jButtonZMinus2 = new javax.swing.JButton();
        jCheckBoxEnableKeyboardJogging2 = new javax.swing.JCheckBox();
        jLabelRemoveFocus2 = new javax.swing.JLabel();
        jButtonReturnToZero2 = new javax.swing.JButton();
        jSliderStepSize2 = new javax.swing.JSlider();
        jPanel20 = new javax.swing.JPanel();
        JDialogLimitations = new javax.swing.JDialog();
        jLabel59 = new javax.swing.JLabel();
        jTableGCodeLog3 = new javax.swing.JTable();
        jButtonOk8 = new javax.swing.JButton();
        jButtonCancel8 = new javax.swing.JButton();
        jLabel60 = new javax.swing.JLabel();
        jPanel1 = new javax.swing.JPanel();
        jLabelRowsInFile = new javax.swing.JLabel();
        jLabelRowsInFile1 = new javax.swing.JLabel();
        jLabelRowsInFile2 = new javax.swing.JLabel();
        jLabelRowsInFile3 = new javax.swing.JLabel();
        jLabelSentRows = new javax.swing.JLabel();
        jLabelRemainingRows = new javax.swing.JLabel();
        jLabelRowsInFile4 = new javax.swing.JLabel();
        jLabelTimeElapsed = new javax.swing.JLabel();
        jLabelRowsInFile5 = new javax.swing.JLabel();
        jProgressBarGCodeProgress = new javax.swing.JProgressBar();
        jPanelGCodeFile = new javax.swing.JPanel();
        jButtonGCodeBrowse = new javax.swing.JButton();
        jTextFieldGCodeFile = new javax.swing.JTextField();
        jLabel5 = new javax.swing.JLabel();
        jButtonGCodePause = new javax.swing.JButton();
        jButtonGCodeSend = new javax.swing.JButton();
        jButtonGCodeCancel = new javax.swing.JButton();
        jButtonGCodeVisualize = new javax.swing.JButton();
        jButtonBrowse = new javax.swing.JButton();
        jButtonVisualise = new javax.swing.JButton();
        jTabbedPane1 = new javax.swing.JTabbedPane();
        jPanel5 = new javax.swing.JPanel();
        jLabel7 = new javax.swing.JLabel();
        jTextFieldCommand = new javax.swing.JTextField();
        jScrollPane2 = new javax.swing.JScrollPane();
        jTextAreaConsole = new javax.swing.JTextArea();
        jButtonClearConsole = new javax.swing.JButton();
        jCheckBoxShowVerboseOutput = new javax.swing.JCheckBox();
        jPanel4 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        jTableGCodeLog = new javax.swing.JTable();
        jButtonClearLog = new javax.swing.JButton();
        jCheckBoxEnableGCodeLog = new javax.swing.JCheckBox();
        jPanelMacros = new javax.swing.JPanel();
        jLabel9 = new javax.swing.JLabel();
        jLabel10 = new javax.swing.JLabel();
        jPanel7 = new javax.swing.JPanel();
        jLabel16 = new javax.swing.JLabel();
        jLabelLastStatusUpdate = new javax.swing.JLabel();
        jLabel17 = new javax.swing.JLabel();
        jLabelMachineHomePosition = new javax.swing.JLabel();
        jPanel6 = new javax.swing.JPanel();
        jSeparator1 = new javax.swing.JSeparator();
        jButtonConnectDisconnect7 = new javax.swing.JButton();
        jComboBox1 = new javax.swing.JComboBox<>();
        jComboBox2 = new javax.swing.JComboBox<>();
        jLabel11 = new javax.swing.JLabel();
        jLabel13 = new javax.swing.JLabel();
        jSeparator3 = new javax.swing.JSeparator();
        jLabelRecommendedRPM = new javax.swing.JLabel();
        jLabel43 = new javax.swing.JLabel();
        jLabel46 = new javax.swing.JLabel();
        jLabel47 = new javax.swing.JLabel();
        jLabelRecommendedRPM1 = new javax.swing.JLabel();
        jLayeredPane1 = new javax.swing.JLayeredPane();
        jPanel2 = new javax.swing.JPanel();
        jButtonResetWorkPosition = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        jLabel6 = new javax.swing.JLabel();
        jLabelWorkPositionZ = new javax.swing.JLabel();
        jLabelWorkPositionX = new javax.swing.JLabel();
        jLabelWorkPositionY = new javax.swing.JLabel();
        jLabelMachinePositionZ = new javax.swing.JLabel();
        jLabelMachinePositionX = new javax.swing.JLabel();
        jLabelMachinePositionY = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel12 = new javax.swing.JLabel();
        jButton1 = new javax.swing.JButton();
        jButton2 = new javax.swing.JButton();
        jButton3 = new javax.swing.JButton();
        jLabelRowsInFile7 = new javax.swing.JLabel();
        jLabelSemiAutoToolChangeStatus = new javax.swing.JLabel();
        jLabel14 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate = new javax.swing.JLabel();
        jLabel15 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM = new javax.swing.JLabel();
        jPanelMachineControl = new javax.swing.JPanel();
        jRadioButtonInches = new javax.swing.JRadioButton();
        jRadioButtonMillimeters = new javax.swing.JRadioButton();
        jLabel4 = new javax.swing.JLabel();
        jSpinnerStep = new javax.swing.JSpinner();
        jPanelJogButtons = new javax.swing.JPanel();
        jButtonYMinus = new javax.swing.JButton();
        jButtonXMinus = new javax.swing.JButton();
        jButtonYPlus = new javax.swing.JButton();
        jButtonXPlus = new javax.swing.JButton();
        jButtonZPlus = new javax.swing.JButton();
        jButtonZMinus = new javax.swing.JButton();
        jCheckBoxEnableKeyboardJogging = new javax.swing.JCheckBox();
        jLabelRemoveFocus = new javax.swing.JLabel();
        jButtonReturnToZero = new javax.swing.JButton();
        jSliderStepSize = new javax.swing.JSlider();
        jPanel17 = new javax.swing.JPanel();
        jPanelConnection = new javax.swing.JPanel();
        jButtonSoftReset = new javax.swing.JButton();
        jLabelMachineX1 = new javax.swing.JLabel();
        jButtonKillAlarm = new javax.swing.JButton();
        jLabelActiveState = new javax.swing.JLabel();
        jButtonConnectDisconnect1 = new javax.swing.JButton();
        jPanel16 = new javax.swing.JPanel();
        jLabel49 = new javax.swing.JLabel();
        jMenuBar1 = new javax.swing.JMenuBar();
        jMenu1 = new javax.swing.JMenu();
        jMenuItemGRBLSettings = new javax.swing.JMenuItem();
        jMenuItemExit = new javax.swing.JMenuItem();
        jMenu2 = new javax.swing.JMenu();
        jMenuSetWorkPos = new javax.swing.JMenuItem();
        jMenuItem2 = new javax.swing.JMenuItem();
        jMenuItemHoleCenterFinder = new javax.swing.JMenuItem();
        jMenu4 = new javax.swing.JMenu();
        jMenuItemStartHomingSequence = new javax.swing.JMenuItem();
        jMenuItemToolChangeSettings = new javax.swing.JMenuItem();
        jMenu5 = new javax.swing.JMenu();
        jMenuItem5 = new javax.swing.JMenuItem();
        jMenuItem9 = new javax.swing.JMenuItem();
        jSeparator19 = new javax.swing.JPopupMenu.Separator();
        jMenuItem10 = new javax.swing.JMenuItem();
        jMenuItem1 = new javax.swing.JMenuItem();
        jSeparator22 = new javax.swing.JPopupMenu.Separator();
        jMenuItem11 = new javax.swing.JMenuItem();
        jMenuItem12 = new javax.swing.JMenuItem();
        jMenu3 = new javax.swing.JMenu();
        jMenuItem3 = new javax.swing.JMenuItem();
        jMenuItem4 = new javax.swing.JMenuItem();
        jMenu6 = new javax.swing.JMenu();
        jMenuItem6 = new javax.swing.JMenuItem();
        jMenuItem7 = new javax.swing.JMenuItem();
        jMenuItem8 = new javax.swing.JMenuItem();

        jDialogLevel.setMinimumSize(new java.awt.Dimension(383, 230));
        jDialogLevel.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        jDialogLevel.setModalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL);

        jPanel13.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Quelle est votre niveau ?", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 0, 24))); // NOI18N

        jLevelCombo.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        jLevelCombo.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Débutant", "Intermédiaire", "Expert" }));
        jLevelCombo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jLevelComboActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel13Layout = new javax.swing.GroupLayout(jPanel13);
        jPanel13.setLayout(jPanel13Layout);
        jPanel13Layout.setHorizontalGroup(
            jPanel13Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel13Layout.createSequentialGroup()
                .addGap(80, 80, 80)
                .addComponent(jLevelCombo, javax.swing.GroupLayout.PREFERRED_SIZE, 182, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jLabel48, javax.swing.GroupLayout.DEFAULT_SIZE, 77, Short.MAX_VALUE)
                .addContainerGap())
        );
        jPanel13Layout.setVerticalGroup(
            jPanel13Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel13Layout.createSequentialGroup()
                .addGap(27, 27, 27)
                .addGroup(jPanel13Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(jLevelCombo, javax.swing.GroupLayout.DEFAULT_SIZE, 34, Short.MAX_VALUE)
                    .addComponent(jLabel48, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap(16, Short.MAX_VALUE))
        );

        jConfirmerBoutton.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        jConfirmerBoutton.setText("Confirmer");
        jConfirmerBoutton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jConfirmerBouttonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogLevelLayout = new javax.swing.GroupLayout(jDialogLevel.getContentPane());
        jDialogLevel.getContentPane().setLayout(jDialogLevelLayout);
        jDialogLevelLayout.setHorizontalGroup(
            jDialogLevelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogLevelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel13, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
            .addGroup(jDialogLevelLayout.createSequentialGroup()
                .addGap(121, 121, 121)
                .addComponent(jConfirmerBoutton, javax.swing.GroupLayout.PREFERRED_SIZE, 116, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialogLevelLayout.setVerticalGroup(
            jDialogLevelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogLevelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel13, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 13, Short.MAX_VALUE)
                .addComponent(jConfirmerBoutton, javax.swing.GroupLayout.PREFERRED_SIZE, 40, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(16, 16, 16))
        );

        jDialog3.setTitle("BIENVENUE");
        jDialog3.setMinimumSize(new java.awt.Dimension(500, 150));
        jDialog3.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        jDialog3.setModalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL);

        jPanel30.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "BIENVENUE", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 0, 12), new java.awt.Color(51, 51, 255))); // NOI18N

        jLabel1.setText("<html>Aides disponibles en haut de l'écran au niveau de l'onglet \"Tutoriel\"<html>");

        javax.swing.GroupLayout jPanel30Layout = new javax.swing.GroupLayout(jPanel30);
        jPanel30.setLayout(jPanel30Layout);
        jPanel30Layout.setHorizontalGroup(
            jPanel30Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel30Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel1, javax.swing.GroupLayout.PREFERRED_SIZE, 397, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel30Layout.setVerticalGroup(
            jPanel30Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel30Layout.createSequentialGroup()
                .addGap(22, 22, 22)
                .addComponent(jLabel1)
                .addContainerGap())
        );

        jButton26.setText("Ok");
        jButton26.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton26ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialog3Layout = new javax.swing.GroupLayout(jDialog3.getContentPane());
        jDialog3.getContentPane().setLayout(jDialog3Layout);
        jDialog3Layout.setHorizontalGroup(
            jDialog3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog3Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jDialog3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel30, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jDialog3Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jButton26)))
                .addContainerGap())
        );
        jDialog3Layout.setVerticalGroup(
            jDialog3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog3Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel30, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButton26)
                .addContainerGap())
        );

        jDialog1.setMinimumSize(new java.awt.Dimension(612, 552));
        jDialog1.setModal(true);
        jDialog1.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jPanelConnection1.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Connection", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelConnection1.setToolTipText("Cette interface permets de vérifier l'état de la connection avec la CNC. \\n Lorsqu'une erreur intervient, la CNC se mets en état \"Arlarm\". Pour continuer ou recommencer la découpe, cliquez sur \"Kill Alarm\", puis \"Soft Reset\". \\n En cas d'erreur de connection, cliquez sur \"Disconnect\" et changez le paramètres de connection dans System --> GRBL Settings");
        jPanelConnection1.setVerifyInputWhenFocusTarget(false);
        jPanelConnection1.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jLabelMachineX2.setText("Status:");
        jPanelConnection1.add(jLabelMachineX2, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 20, 40, 20));

        jLabelActiveState1.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jLabelActiveState1.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelActiveState1.setText("Idle...");
        jPanelConnection1.add(jLabelActiveState1, new org.netbeans.lib.awtextra.AbsoluteConstraints(70, 20, 120, 20));

        jButtonConnectDisconnect2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonConnectDisconnect2.setText("Disconnect");
        jButtonConnectDisconnect2.setName("jButtonConnectDisconnect"); // NOI18N
        jPanelConnection1.add(jButtonConnectDisconnect2, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 50, -1, -1));

        jButtonKillAlarm2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonKillAlarm2.setText("Kill Alarm");
        jPanelConnection1.add(jButtonKillAlarm2, new org.netbeans.lib.awtextra.AbsoluteConstraints(190, 20, -1, -1));

        jButtonKillAlarm3.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonKillAlarm3.setText("Kill Alarm");
        jPanelConnection1.add(jButtonKillAlarm3, new org.netbeans.lib.awtextra.AbsoluteConstraints(190, 20, -1, -1));

        jButtonSoftReset2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonSoftReset2.setText("Soft Reset");
        jPanelConnection1.add(jButtonSoftReset2, new org.netbeans.lib.awtextra.AbsoluteConstraints(120, 50, -1, -1));

        jLabel8.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel8.setText("<html> <h3 text-decoration=\"bold\"> Interface de Connection");
        jLabel8.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel18.setText("<html> Cette interface permets de vérifier la connection avec la CNC. <br><br>   Le status<i> Idle</i> indique aue la CNC attends des ordres  <br>    Le status <i>Working</i> indique de la CNC effectue des ordres </html>");

        jPanel8.setBorder(javax.swing.BorderFactory.createTitledBorder("En cas d'erreur"));

        jButtonKillAlarm1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonKillAlarm1.setText("Kill Alarm");

        jButtonSoftReset1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonSoftReset1.setText("Soft Reset");

        jLabel19.setText("<html>\nStatus:  <i color=\"red\"> Alarm </i> indique qu'une erreur est survenue. <br>\n<br>\nSi un limit switch est enclenché ( led allumée ), décalez l'axe jusqu'a ce que la led s'éteigne \n</html>");

        jLabel20.setText("Cliquez sur ");

        jLabel21.setText("puis sur");

        javax.swing.GroupLayout jPanel8Layout = new javax.swing.GroupLayout(jPanel8);
        jPanel8.setLayout(jPanel8Layout);
        jPanel8Layout.setHorizontalGroup(
            jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel8Layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel19, javax.swing.GroupLayout.PREFERRED_SIZE, 360, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel8Layout.createSequentialGroup()
                        .addGap(29, 29, 29)
                        .addComponent(jLabel20, javax.swing.GroupLayout.PREFERRED_SIZE, 69, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonKillAlarm1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel21, javax.swing.GroupLayout.PREFERRED_SIZE, 43, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonSoftReset1)))
                .addGap(114, 114, 114))
        );
        jPanel8Layout.setVerticalGroup(
            jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel8Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel19, javax.swing.GroupLayout.PREFERRED_SIZE, 72, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 26, Short.MAX_VALUE)
                .addGroup(jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel20)
                    .addComponent(jButtonKillAlarm1)
                    .addComponent(jLabel21)
                    .addComponent(jButtonSoftReset1))
                .addGap(16, 16, 16))
        );

        jButton7.setText("Suivant");
        jButton7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton7ActionPerformed(evt);
            }
        });

        jButton8.setText("Quitter");
        jButton8.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton8ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialog1Layout = new javax.swing.GroupLayout(jDialog1.getContentPane());
        jDialog1.getContentPane().setLayout(jDialog1Layout);
        jDialog1Layout.setHorizontalGroup(
            jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator4)
            .addGroup(jDialog1Layout.createSequentialGroup()
                .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jLabel8))
                    .addComponent(jPanel8, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jDialog1Layout.createSequentialGroup()
                                .addGap(19, 19, 19)
                                .addComponent(jPanelConnection1, javax.swing.GroupLayout.PREFERRED_SIZE, 292, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jLabel18, javax.swing.GroupLayout.PREFERRED_SIZE, 265, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jDialog1Layout.createSequentialGroup()
                                .addGap(201, 201, 201)
                                .addComponent(jButton7, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(32, 32, 32)
                                .addComponent(jButton8, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 18, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jDialog1Layout.setVerticalGroup(
            jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator4, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(79, 79, 79)
                .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addGap(21, 21, 21)
                        .addComponent(jPanelConnection1, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jLabel18, javax.swing.GroupLayout.PREFERRED_SIZE, 149, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addComponent(jPanel8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton7)
                    .addComponent(jButton8))
                .addGap(29, 29, 29))
        );

        jDialog2.setMinimumSize(new java.awt.Dimension(612, 600));
        jDialog2.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        jDialog2.setModalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL);

        jLabel22.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel22.setText("<html> <h3 text-decoration=\"bold\"> Interface de Connection");
        jLabel22.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel23.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel23.setText("<html> <h4><center>Cette interface permets de voir laposition de la CNC </center></h4>  \nLa position des axes XYZ est affichée. ( Ne pas oublier de <i color=\"blue\"> Home </i> la CNC) <br>\nØ permets de tare l'axe de la CNC");
        jLabel23.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jPanel10.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Status", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel10.setToolTipText("Les informations de la CNC en temps réel sont affichées ici. En appuyant sur |Ø|, l'axe est remis à 0. ");
        jPanel10.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonResetWorkPosition1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonResetWorkPosition1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonResetWorkPosition1.setText("Ø  Zero Work Position");
        jButtonResetWorkPosition1.setToolTipText("Reset the Work Position to 0,0,0");
        jButtonResetWorkPosition1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonResetWorkPosition1ActionPerformed(evt);
            }
        });
        jPanel10.add(jButtonResetWorkPosition1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 240, 32));

        jPanel11.setLayout(new java.awt.GridLayout(1, 0));

        jLabel27.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabel27.setForeground(new java.awt.Color(0, 75, 127));
        jPanel11.add(jLabel27);

        jPanel10.add(jPanel11, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 70, 270, -1));

        jLabelWorkPositionZ1.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionZ1.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionZ1.setText("Z0");
        jLabelWorkPositionZ1.setToolTipText("Z Work Position");
        jLabelWorkPositionZ1.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionZ1MouseClicked(evt);
            }
        });
        jPanel10.add(jLabelWorkPositionZ1, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 90, 100, 20));

        jLabelWorkPositionX1.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionX1.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionX1.setText("X0");
        jLabelWorkPositionX1.setToolTipText("X Work Position");
        jLabelWorkPositionX1.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionX1MouseClicked(evt);
            }
        });
        jPanel10.add(jLabelWorkPositionX1, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 30, 100, 20));

        jLabelWorkPositionY1.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionY1.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionY1.setText("Y0");
        jLabelWorkPositionY1.setToolTipText("Y Work Position");
        jLabelWorkPositionY1.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionY1MouseClicked(evt);
            }
        });
        jPanel10.add(jLabelWorkPositionY1, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 60, 100, 20));

        jLabelMachinePositionZ1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionZ1.setText("Z0");
        jLabelMachinePositionZ1.setToolTipText("Z Machine Position");
        jPanel10.add(jLabelMachinePositionZ1, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 90, 60, 20));

        jLabelMachinePositionX1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionX1.setText("X0");
        jLabelMachinePositionX1.setToolTipText("X Machine Position");
        jPanel10.add(jLabelMachinePositionX1, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 30, 60, 20));

        jLabelMachinePositionY1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionY1.setText("Y0");
        jLabelMachinePositionY1.setToolTipText("Y Machine Position");
        jPanel10.add(jLabelMachinePositionY1, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 60, 60, 20));

        jLabel28.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel28.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel28.setText("Z:");
        jPanel10.add(jLabel28, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 90, 20, 20));

        jLabel29.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel29.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel29.setText("X:");
        jPanel10.add(jLabel29, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 30, 20, 20));

        jLabel30.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel30.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel30.setText("Y:");
        jPanel10.add(jLabel30, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 60, 20, 20));

        jButton4.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton4.setText("Ø");
        jButton4.setToolTipText("Click to Zero Z Work Position");
        jButton4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton4ActionPerformed(evt);
            }
        });
        jPanel10.add(jButton4, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 30, 28));

        jButton5.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton5.setText("Ø");
        jButton5.setToolTipText("Click to Zero X Work Position");
        jButton5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton5ActionPerformed(evt);
            }
        });
        jPanel10.add(jButton5, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 30, 30, 28));

        jButton6.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton6.setText("Ø");
        jButton6.setToolTipText("Click to Zero Y Work Position");
        jButton6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton6ActionPerformed(evt);
            }
        });
        jPanel10.add(jButton6, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 60, 30, 28));

        jLabelRowsInFile8.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRowsInFile8.setText("Semi Auto Tool Change:");
        jPanel10.add(jLabelRowsInFile8, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 210, 150, 20));

        jLabelSemiAutoToolChangeStatus1.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabelSemiAutoToolChangeStatus1.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelSemiAutoToolChangeStatus1.setText("Off");
        jPanel10.add(jLabelSemiAutoToolChangeStatus1, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 210, 80, 20));

        jLabel31.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel31.setText("Feedrate:");
        jPanel10.add(jLabel31, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 170, 150, 20));

        jLabelRealTimeFeedRate1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate1.setText("0mm/min");
        jPanel10.add(jLabelRealTimeFeedRate1, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 170, 80, 20));

        jLabel32.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel32.setText("Spindle RPM:");
        jPanel10.add(jLabel32, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 190, 150, 20));

        jLabelRealTimeSpindleRPM1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM1.setText("0");
        jPanel10.add(jLabelRealTimeSpindleRPM1, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 190, 80, 20));

        jLabel33.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel33.setText("Feedrate:");

        jLabelRealTimeFeedRate2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate2.setText("0mm/min");

        jLabel34.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel34.setText("Spindle RPM:");

        jLabelRealTimeSpindleRPM2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM2.setText("0");

        jLabel24.setText("<html> La vitesse de Rotation et d'Avancement peuvent être l'origine des dangers de la CNC.  Ce tutoriel vous apprendras par la suite comment régler ces valeurs pour une découpe sans accros.</html> ");

        jButtonCancel.setText("Quitter");
        jButtonCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancelActionPerformed(evt);
            }
        });

        jButtonOk.setText("Suivant");
        jButtonOk.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOkActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialog2Layout = new javax.swing.GroupLayout(jDialog2.getContentPane());
        jDialog2.getContentPane().setLayout(jDialog2Layout);
        jDialog2Layout.setHorizontalGroup(
            jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator6)
            .addGroup(jDialog2Layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialog2Layout.createSequentialGroup()
                        .addComponent(jLabel33, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(10, 10, 10)
                        .addComponent(jLabelRealTimeFeedRate2, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jDialog2Layout.createSequentialGroup()
                        .addComponent(jLabel34, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(10, 10, 10)
                        .addComponent(jLabelRealTimeSpindleRPM2, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(0, 0, Short.MAX_VALUE))
            .addGroup(jDialog2Layout.createSequentialGroup()
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator7)
                    .addGroup(jDialog2Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel24, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE)
                            .addComponent(jLabel22, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel23)))
                    .addGroup(jDialog2Layout.createSequentialGroup()
                        .addGap(163, 163, 163)
                        .addComponent(jPanel10, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
            .addGroup(jDialog2Layout.createSequentialGroup()
                .addGap(206, 206, 206)
                .addComponent(jButtonOk, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 0, Short.MAX_VALUE))
        );
        jDialog2Layout.setVerticalGroup(
            jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel22, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator6, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel10, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator7, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel23, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel33, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeFeedRate2, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel34, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeSpindleRPM2, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel24, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk)
                    .addComponent(jButtonCancel))
                .addContainerGap(29, Short.MAX_VALUE))
        );

        jDialogGSender.setMinimumSize(new java.awt.Dimension(612, 552));
        jDialogGSender.setModal(true);
        jDialogGSender.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel25.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel25.setText("<html> <h3 text-decoration=\"bold\"> Interface de sélection G Code");
        jLabel25.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel26.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel26.setText("<html> <h2><center>Importer l'adresse du fichier a découper </center> </h2>   Il est possible de mettre en pause ou annuler la découpe ici cependant l'installation d'un bouton d'URGENCE est recommandé !<br><br> Le bouton <i> Sélectionner</i> permets d'importer un fichier gcode depuis son ordinateur et <i>Visualiser</i> permets de voir un apercu des chemins de prendra la CNC. <br> L'avancée de l'impression s'affiche sur la barre de progrès </html> ");
        jLabel26.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        jLabel26.setVerifyInputWhenFocusTarget(false);

        jButtonCancel1.setText("Quitter");
        jButtonCancel1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel1ActionPerformed(evt);
            }
        });

        jButtonOk1.setText("Suivant");
        jButtonOk1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk1ActionPerformed(evt);
            }
        });

        jPanel9.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "G-Code File", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel9.setToolTipText("Ce panneau permets d'importer et visualiser les détails de découpe du fichier. ");
        jPanel9.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jLabelRowsInFile6.setText("0");
        jPanel9.add(jLabelRowsInFile6, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 90, 54, -1));

        jLabelRowsInFile14.setText("Sent Rows:");
        jPanel9.add(jLabelRowsInFile14, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 110, 80, -1));

        jLabelRowsInFile15.setText("Remaining Rows:");
        jPanel9.add(jLabelRowsInFile15, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 100, -1));

        jLabelRowsInFile16.setText("Rows in file:");
        jPanel9.add(jLabelRowsInFile16, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 80, -1));

        jLabelSentRows1.setText("0");
        jPanel9.add(jLabelSentRows1, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 110, 54, -1));

        jLabelRemainingRows1.setText("0");
        jPanel9.add(jLabelRemainingRows1, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 130, 54, -1));

        jLabelRowsInFile17.setText("Time elapsed:");
        jPanel9.add(jLabelRowsInFile17, new org.netbeans.lib.awtextra.AbsoluteConstraints(220, 90, -1, -1));

        jLabelTimeElapsed1.setText("00:00:00");
        jPanel9.add(jLabelTimeElapsed1, new org.netbeans.lib.awtextra.AbsoluteConstraints(300, 90, 146, -1));

        jLabelRowsInFile18.setText("Progress:");
        jPanel9.add(jLabelRowsInFile18, new org.netbeans.lib.awtextra.AbsoluteConstraints(220, 110, 66, -1));

        jProgressBarGCodeProgress1.setPreferredSize(new java.awt.Dimension(146, 16));
        jPanel9.add(jProgressBarGCodeProgress1, new org.netbeans.lib.awtextra.AbsoluteConstraints(300, 110, 230, -1));

        jTextFieldGCodeFile1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextFieldGCodeFile1ActionPerformed(evt);
            }
        });

        jLabel88.setText("File:");

        jButtonGCodeBrowse1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeBrowse1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeBrowse1.setText("Browse");
        jButtonGCodeBrowse1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeBrowse1ActionPerformed(evt);
            }
        });

        jButtonGCodePause1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodePause1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodePause1.setText("Pause");
        jButtonGCodePause1.setEnabled(false);
        jButtonGCodePause1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodePause1ActionPerformed(evt);
            }
        });

        jButtonGCodeSend1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeSend1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeSend1.setText("Send");
        jButtonGCodeSend1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeSend1ActionPerformed(evt);
            }
        });

        jButtonGCodeCancel1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeCancel1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeCancel1.setText("Cancel");
        jButtonGCodeCancel1.setEnabled(false);
        jButtonGCodeCancel1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeCancel1ActionPerformed(evt);
            }
        });

        jButtonGCodeVisualize1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeVisualize1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeVisualize1.setText("Visualize");
        jButtonGCodeVisualize1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeVisualize1ActionPerformed(evt);
            }
        });

        jButtonBrowse2.setText("Sélectionner");
        jButtonBrowse2.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonBrowse2MouseMoved(evt);
            }
        });
        jButtonBrowse2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonBrowse2ActionPerformed(evt);
            }
        });

        jButtonVisualise2.setText("Visualiser");
        jButtonVisualise2.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonVisualise2MouseMoved(evt);
            }
        });
        jButtonVisualise2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonVisualise2ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanelGCodeFile1Layout = new javax.swing.GroupLayout(jPanelGCodeFile1);
        jPanelGCodeFile1.setLayout(jPanelGCodeFile1Layout);
        jPanelGCodeFile1Layout.setHorizontalGroup(
            jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                        .addComponent(jButtonGCodeSend1, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodePause1, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodeCancel1, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                        .addComponent(jLabel88, javax.swing.GroupLayout.PREFERRED_SIZE, 26, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTextFieldGCodeFile1)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonVisualise2)
                    .addComponent(jButtonBrowse2))
                .addGap(500, 500, 500)
                .addComponent(jButtonGCodeVisualize1)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButtonGCodeBrowse1, javax.swing.GroupLayout.PREFERRED_SIZE, 76, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );
        jPanelGCodeFile1Layout.setVerticalGroup(
            jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                        .addComponent(jButtonBrowse2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonVisualise2))
                    .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                        .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel88)
                            .addComponent(jTextFieldGCodeFile1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jButtonGCodePause1)
                            .addComponent(jButtonGCodeSend1)
                            .addComponent(jButtonGCodeCancel1)
                            .addComponent(jButtonGCodeVisualize1)
                            .addComponent(jButtonGCodeBrowse1))))
                .addContainerGap(14, Short.MAX_VALUE))
        );

        jPanel9.add(jPanelGCodeFile1, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 20, 530, 70));

        jProgressBarGCodeProgress2.setValue(50);
        jProgressBarGCodeProgress2.setPreferredSize(new java.awt.Dimension(146, 16));

        jLabelRowsInFile19.setText("Progress:");

        javax.swing.GroupLayout jDialogGSenderLayout = new javax.swing.GroupLayout(jDialogGSender.getContentPane());
        jDialogGSender.getContentPane().setLayout(jDialogGSenderLayout);
        jDialogGSenderLayout.setHorizontalGroup(
            jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator8)
            .addGroup(jDialogGSenderLayout.createSequentialGroup()
                .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator9)
                    .addGroup(jDialogGSenderLayout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel25, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jPanel9, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE)))
                    .addComponent(jLabel26, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE))
                .addContainerGap())
            .addGroup(jDialogGSenderLayout.createSequentialGroup()
                .addGap(103, 103, 103)
                .addComponent(jLabelRowsInFile19, javax.swing.GroupLayout.PREFERRED_SIZE, 66, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(14, 14, 14)
                .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jProgressBarGCodeProgress2, javax.swing.GroupLayout.PREFERRED_SIZE, 230, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jDialogGSenderLayout.createSequentialGroup()
                        .addGap(14, 14, 14)
                        .addComponent(jButtonOk1, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(32, 32, 32)
                        .addComponent(jButtonCancel1, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialogGSenderLayout.setVerticalGroup(
            jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogGSenderLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel25, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator8, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel9, javax.swing.GroupLayout.PREFERRED_SIZE, 154, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jSeparator9, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jLabel26, javax.swing.GroupLayout.PREFERRED_SIZE, 151, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabelRowsInFile19)
                    .addComponent(jProgressBarGCodeProgress2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 35, Short.MAX_VALUE)
                .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk1)
                    .addComponent(jButtonCancel1))
                .addContainerGap(21, Short.MAX_VALUE))
        );

        jDialogTab1.setMinimumSize(new java.awt.Dimension(612, 512));
        jDialogTab1.setModal(true);
        jDialogTab1.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel44.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel44.setText("<html> <h3 text-decoration=\"bold\"> Interface d'envoie GCODE");
        jLabel44.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel45.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel45.setText("<html> <h4><center>Cette interface envoie des commandes GCODE</center></h4>   Il est possible de controler les paramètres de la CNC, sa configuration et ses déplacements via des commandes G Code.<br> <br> Un tutoriel plus complet est accessible dans le menu <i>Tutoriel</i> puis <i> GCode Apprendre les bases </i>");
        jLabel45.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jButtonCancel2.setText("Quitter");
        jButtonCancel2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel2ActionPerformed(evt);
            }
        });

        jButtonOk2.setText("Suivant");
        jButtonOk2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk2ActionPerformed(evt);
            }
        });

        jLabel35.setText("Command:");

        jTextFieldCommand1.setToolTipText("Send a command to the controller");
        jTextFieldCommand1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextFieldCommand1ActionPerformed(evt);
            }
        });

        jTextAreaConsole1.setEditable(false);
        jTextAreaConsole1.setColumns(20);
        jTextAreaConsole1.setFont(new java.awt.Font("Tahoma", 0, 14)); // NOI18N
        jTextAreaConsole1.setForeground(new java.awt.Color(255, 255, 255));
        jTextAreaConsole1.setRows(5);
        jScrollPane6.setViewportView(jTextAreaConsole1);

        jButtonClearConsole1.setText("Clear Console");
        jButtonClearConsole1.setToolTipText("Clear the GCode Log");
        jButtonClearConsole1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonClearConsole1ActionPerformed(evt);
            }
        });

        jCheckBoxShowVerboseOutput1.setText("Show verbose output");
        jCheckBoxShowVerboseOutput1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxShowVerboseOutput1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel12Layout = new javax.swing.GroupLayout(jPanel12);
        jPanel12.setLayout(jPanel12Layout);
        jPanel12Layout.setHorizontalGroup(
            jPanel12Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel12Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel12Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel12Layout.createSequentialGroup()
                        .addComponent(jLabel35)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTextFieldCommand1))
                    .addGroup(jPanel12Layout.createSequentialGroup()
                        .addComponent(jButtonClearConsole1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jCheckBoxShowVerboseOutput1)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(jScrollPane6, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 434, Short.MAX_VALUE))
                .addContainerGap())
        );
        jPanel12Layout.setVerticalGroup(
            jPanel12Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel12Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel12Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel35)
                    .addComponent(jTextFieldCommand1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane6, javax.swing.GroupLayout.DEFAULT_SIZE, 197, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel12Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonClearConsole1)
                    .addComponent(jCheckBoxShowVerboseOutput1))
                .addGap(10, 10, 10))
        );

        javax.swing.GroupLayout jDialogTab1Layout = new javax.swing.GroupLayout(jDialogTab1.getContentPane());
        jDialogTab1.getContentPane().setLayout(jDialogTab1Layout);
        jDialogTab1Layout.setHorizontalGroup(
            jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator10)
            .addGroup(jDialogTab1Layout.createSequentialGroup()
                .addGroup(jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator11)
                    .addGroup(jDialogTab1Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel44, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel45, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE)))
                    .addGroup(jDialogTab1Layout.createSequentialGroup()
                        .addGroup(jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jDialogTab1Layout.createSequentialGroup()
                                .addGap(201, 201, 201)
                                .addComponent(jButtonOk2, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(32, 32, 32)
                                .addComponent(jButtonCancel2, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jDialogTab1Layout.createSequentialGroup()
                                .addGap(83, 83, 83)
                                .addComponent(jPanel12, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 71, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jDialogTab1Layout.setVerticalGroup(
            jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel44, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator10, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(jPanel12, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator11, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel45, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(20, 20, 20)
                .addGroup(jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk2)
                    .addComponent(jButtonCancel2))
                .addContainerGap())
        );

        jDialogTab2.setModal(true);
        jDialogTab2.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel55.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel55.setText("<html> <h3 text-decoration=\"bold\">Macros");
        jLabel55.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel56.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel56.setText("<html> <h4 text-align=\"center\">Cette interface permets de voir le GCode envoyé a la CNC </h4>   Chaque commande s'affiche ici ainsi que son état. ");
        jLabel56.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jButtonCancel3.setText("Quitter");
        jButtonCancel3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel3ActionPerformed(evt);
            }
        });

        jButtonOk3.setText("Suivant");
        jButtonOk3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk3ActionPerformed(evt);
            }
        });

        jTableGCodeLog1.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Row", "Command", "TX", "RX", "Error"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.Object.class, java.lang.Object.class, java.lang.Boolean.class, java.lang.Boolean.class, java.lang.Object.class
            };
            boolean[] canEdit = new boolean [] {
                true, true, false, false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        jScrollPane3.setViewportView(jTableGCodeLog1);
        if (jTableGCodeLog1.getColumnModel().getColumnCount() > 0) {
            jTableGCodeLog1.getColumnModel().getColumn(0).setMinWidth(20);
            jTableGCodeLog1.getColumnModel().getColumn(0).setPreferredWidth(20);
            jTableGCodeLog1.getColumnModel().getColumn(2).setMinWidth(50);
            jTableGCodeLog1.getColumnModel().getColumn(2).setPreferredWidth(50);
            jTableGCodeLog1.getColumnModel().getColumn(2).setMaxWidth(50);
            jTableGCodeLog1.getColumnModel().getColumn(3).setMinWidth(50);
            jTableGCodeLog1.getColumnModel().getColumn(3).setPreferredWidth(50);
            jTableGCodeLog1.getColumnModel().getColumn(3).setMaxWidth(50);
        }

        jButtonClearLog1.setText("Clear Log");
        jButtonClearLog1.setToolTipText("Clear the GCode Log");
        jButtonClearLog1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonClearLog1ActionPerformed(evt);
            }
        });

        jCheckBoxEnableGCodeLog1.setText("Enable GCode Log");
        jCheckBoxEnableGCodeLog1.setToolTipText("You may uncheck it on slower computers");
        jCheckBoxEnableGCodeLog1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxEnableGCodeLog1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogTab2Layout = new javax.swing.GroupLayout(jDialogTab2.getContentPane());
        jDialogTab2.getContentPane().setLayout(jDialogTab2Layout);
        jDialogTab2Layout.setHorizontalGroup(
            jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator12)
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addGap(155, 155, 155)
                .addComponent(jButtonOk3, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel3, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator13)
                    .addGroup(jDialogTab2Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel55, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel56, javax.swing.GroupLayout.DEFAULT_SIZE, 525, Short.MAX_VALUE)
                            .addGroup(jDialogTab2Layout.createSequentialGroup()
                                .addComponent(jButtonClearLog1)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(jCheckBoxEnableGCodeLog1))
                            .addComponent(jScrollPane3, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 525, Short.MAX_VALUE))))
                .addContainerGap())
        );
        jDialogTab2Layout.setVerticalGroup(
            jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel55, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator12, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jScrollPane3, javax.swing.GroupLayout.PREFERRED_SIZE, 178, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(22, 22, 22)
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonClearLog1)
                    .addComponent(jCheckBoxEnableGCodeLog1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jSeparator13, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel56, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(39, 39, 39)
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk3)
                    .addComponent(jButtonCancel3))
                .addContainerGap(37, Short.MAX_VALUE))
        );

        jDialogTab3.setModal(true);
        jDialogTab3.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel66.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel66.setText("<html> <h3 text-decoration=\"bold\"> Macros");
        jLabel66.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel67.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel67.setText("<html> <h4 text-align=\"center\">Cette interface permets de voir rapidement envoyer une commande a la CNC </h4>   Il suffit de définir une commande pour chaque bouton, puis de cliquer le bouton popur lancer la commande. <br> Nous recommandons de mettre des commandes telle que ");
        jLabel67.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jButtonCancel4.setText("Quitter");
        jButtonCancel4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel4ActionPerformed(evt);
            }
        });

        jButtonOk4.setText("Suivant");
        jButtonOk4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk4ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogTab3Layout = new javax.swing.GroupLayout(jDialogTab3.getContentPane());
        jDialogTab3.getContentPane().setLayout(jDialogTab3Layout);
        jDialogTab3Layout.setHorizontalGroup(
            jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator14)
            .addGroup(jDialogTab3Layout.createSequentialGroup()
                .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator15)
                    .addGroup(jDialogTab3Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel66, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel67, javax.swing.GroupLayout.DEFAULT_SIZE, 386, Short.MAX_VALUE))))
                .addContainerGap())
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jDialogTab3Layout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(jButtonOk4, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel4, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(101, 101, 101))
        );
        jDialogTab3Layout.setVerticalGroup(
            jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab3Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel66, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator14, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(252, 252, 252)
                .addComponent(jSeparator15, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel67, javax.swing.GroupLayout.PREFERRED_SIZE, 148, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 46, Short.MAX_VALUE)
                .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk4)
                    .addComponent(jButtonCancel4))
                .addGap(20, 20, 20))
        );

        jDialogTab4.setMinimumSize(new java.awt.Dimension(458, 657));
        jDialogTab4.setModal(true);
        jDialogTab4.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel77.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel77.setText("<html> <h3 text-decoration=\"bold\"> Informations");
        jLabel77.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel78.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel78.setText("<html> <h4 text-align=\"center\">Cette interface permets de voir les informations de la CNC </h4>  ");
        jLabel78.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jButtonCancel5.setText("Quitter");
        jButtonCancel5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel5ActionPerformed(evt);
            }
        });

        jButtonOk5.setText("Suivant");
        jButtonOk5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk5ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogTab4Layout = new javax.swing.GroupLayout(jDialogTab4.getContentPane());
        jDialogTab4.getContentPane().setLayout(jDialogTab4Layout);
        jDialogTab4Layout.setHorizontalGroup(
            jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator16)
            .addGroup(jDialogTab4Layout.createSequentialGroup()
                .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator17)
                    .addGroup(jDialogTab4Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel77, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel78, javax.swing.GroupLayout.DEFAULT_SIZE, 386, Short.MAX_VALUE))))
                .addContainerGap())
            .addGroup(jDialogTab4Layout.createSequentialGroup()
                .addGap(101, 101, 101)
                .addComponent(jButtonOk5, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel5, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialogTab4Layout.setVerticalGroup(
            jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel77, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator16, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(252, 252, 252)
                .addComponent(jSeparator17, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel78, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 120, Short.MAX_VALUE)
                .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk5)
                    .addComponent(jButtonCancel5))
                .addGap(33, 33, 33))
        );

        jFrame1.setMinimumSize(new java.awt.Dimension(930, 805));
        jFrame1.setResizable(false);

        jLabel36.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel36.setText("<html>  <h1> Apprenez le G Code </h1> </html>");

        jLabel37.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel37.setText("<html> G-code est un langage de programmation utilisé pour contrôler les machines CNC. Il indique à la machine quoi faire et comment le faire, notamment le mouvement, la vitesse et la sélection d'outil. G-code est utilisé pour transformer un modèle 3D en un ensemble d'instructions que la machine CNC peut suivre. </html>");

        jTable1.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        jTable1.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {
                {"S", "Vitesse de rotation de la broche. Contrôle la vitesse de rotation de la broche qui fait tourner l'outil de coupe. "},
                {"F", "Vitesse d'alimentation. Contrôle la vitesse d'avance de l'outil par unité de temps. "},
                {"G00", "Positionnement rapide. Déplace rapidement la machine vers un point spécifique sans couper ni déplacer de matériau. "},
                {"G01", "Interpolation linéaire. Déplace la machine en ligne droite d'un point à un autre. "},
                {"G02", "Déplace la machine dans un arc dans le sens horaire d'un point à un autre. L'arc est défini par un point central et un rayon. "},
                {"G03", "Déplace la machine dans un arc dans le sens antihoraire d'un point à un autre. L'arc est défini par un point central et un rayon. "},
                {"G04", "Attente. Indique à la machine de faire une pause pendant une durée spécifiée. Le temps est défini par la commande P"},
                {"G17", "Plan de déplacement XY. Définit le plan de déplacement XY pour les commandes de déplacement linéaire et circulaire."},
                {"G18", "Plan de déplacement XZ. Définit le plan de déplacement XZ pour les commandes de déplacement linéaire et circulaire."},
                {"G19", "Plan de déplacement YZ. Définit le plan de déplacement YZ pour les commandes de déplacement linéaire et circulaire."},
                {"G20", "Mode pouce. Cette commande permet à la machine d'utiliser des pouces pour les mesures."},
                {"G21", "Mode millimètre. Cette commande permet à la machine d'utiliser des millimètres pour les mesures"},
                {"G28", "Position de référence. Déplace la machine vers sa position de référence."},
                {"G40", "Désactive la compensation de rayon d'outil, qui ajuste le chemin de l'outil pour tenir compte du rayon de l'outil de coupe"},
                {"G41", "Ative la compensation de rayon d'outil vers la gauche, qui ajuste le chemin de l'outil pour tenir compte du rayon de l'outil de coupe"},
                {"G42", "Active la compensation de rayon d'outil vers la droite, qui ajuste le chemin de l'outil pour tenir compte du rayon de l'outil de coupe."},
                {"G90", "Mode absolu. Toutes les positions sont mesurées à partir de l'origine de la machine"},
                {"G91", "Mode incrémental. Toutes les positions sont mesurées à partir de la position actuelle de la machine"},
                {"M03", "Activation de la broche dans le sens horaire. Fait tourner l'outil de coupe."},
                {"M04", "Activation de la broche dans le sens antihoraire. Fait tourner l'outil de coupe."},
                {"M05", "Désactivation de la broche. Arrête la rotation de l'outil de coupe."},
                {"M08", "Activation du liquide de refroidissement."},
                {"M09", "Désactivation du liquide de refroidissement. "}
            },
            new String [] {
                "G CODE", "Explication"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class
            };
            boolean[] canEdit = new boolean [] {
                false, true
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        jTable1.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_ALL_COLUMNS);
        jTable1.setColumnSelectionAllowed(true);
        jTable1.setDebugGraphicsOptions(javax.swing.DebugGraphics.NONE_OPTION);
        jTable1.setEditingColumn(0);
        jTable1.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        jTable1.setShowGrid(true);
        jTable1.getTableHeader().setReorderingAllowed(false);
        jScrollPane7.setViewportView(jTable1);
        jTable1.getColumnModel().getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        if (jTable1.getColumnModel().getColumnCount() > 0) {
            jTable1.getColumnModel().getColumn(0).setMinWidth(55);
            jTable1.getColumnModel().getColumn(0).setPreferredWidth(50);
            jTable1.getColumnModel().getColumn(0).setMaxWidth(55);
            jTable1.getColumnModel().getColumn(1).setResizable(false);
        }

        jButton9.setLabel("Voir des exemples");
        jButton9.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton9ActionPerformed(evt);
            }
        });

        jButton10.setLabel("Quitter le cours");
        jButton10.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton10ActionPerformed(evt);
            }
        });

        jButton11.setLabel("+ d'information");
        jButton11.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton11ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jFrame1Layout = new javax.swing.GroupLayout(jFrame1.getContentPane());
        jFrame1.getContentPane().setLayout(jFrame1Layout);
        jFrame1Layout.setHorizontalGroup(
            jFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jLabel36)
            .addComponent(jSeparator2)
            .addGroup(jFrame1Layout.createSequentialGroup()
                .addGroup(jFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jFrame1Layout.createSequentialGroup()
                        .addGap(14, 14, 14)
                        .addGroup(jFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel37, javax.swing.GroupLayout.PREFERRED_SIZE, 910, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jScrollPane7, javax.swing.GroupLayout.PREFERRED_SIZE, 894, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(jFrame1Layout.createSequentialGroup()
                        .addGap(217, 217, 217)
                        .addComponent(jButton9, javax.swing.GroupLayout.PREFERRED_SIZE, 129, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(32, 32, 32)
                        .addComponent(jButton10, javax.swing.GroupLayout.PREFERRED_SIZE, 129, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(32, 32, 32)
                        .addComponent(jButton11, javax.swing.GroupLayout.PREFERRED_SIZE, 129, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jFrame1Layout.setVerticalGroup(
            jFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jFrame1Layout.createSequentialGroup()
                .addComponent(jLabel36, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator2, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jLabel37, javax.swing.GroupLayout.PREFERRED_SIZE, 94, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane7, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(27, 27, 27)
                .addGroup(jFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton9, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton10, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton11, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jDialogMiseEnPlace.setName("jDialogMiseEnPlace"); // NOI18N

        jLabel60.setIcon(new javax.swing.ImageIcon(getClass().getResource("/sourcerabbit/gcode/sender/UI/Images/CNC_WorkHold (1).png"))); // NOI18N

        javax.swing.GroupLayout jPanel15Layout = new javax.swing.GroupLayout(jPanel15);
        jPanel15.setLayout(jPanel15Layout);
        jPanel15Layout.setHorizontalGroup(
            jPanel15Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel15Layout.createSequentialGroup()
                .addContainerGap(121, Short.MAX_VALUE)
                .addComponent(jLabel60)
                .addGap(107, 107, 107))
        );
        jPanel15Layout.setVerticalGroup(
            jPanel15Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel15Layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(jLabel60, javax.swing.GroupLayout.PREFERRED_SIZE, 309, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(16, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout jPanel14Layout = new javax.swing.GroupLayout(jPanel14);
        jPanel14.setLayout(jPanel14Layout);
        jPanel14Layout.setHorizontalGroup(
            jPanel14Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel14Layout.createSequentialGroup()
                .addGap(82, 82, 82)
                .addComponent(jPanel15, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel14Layout.setVerticalGroup(
            jPanel14Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel15, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );

        jLabel38.setText("<html>\nAvant de commencer toute opération sur le logiciel CNC, assurez-vous que le matériel est correctement installé sur le lit de la machine. <br><br> Fixez fermement les pièces à usiner avec des brides ou des étaux, en prenant soin de ne pas endommager les surfaces à usiner. <br><br>Vérifiez que le matériel est bien aligné et que les outils de coupe ne risquent pas de toucher les pièces lors de l'usinage. Assurez-vous également que la zone de travail est dégagée de tout objet qui pourrait interférer avec le mouvement de la machine.");

        jButton12.setText("Défnir la zone de travail");
        jButton12.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton12ActionPerformed(evt);
            }
        });

        jButton13.setText("Suivant");
        jButton13.setEnabled(false);
        jButton13.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton13ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogMiseEnPlaceLayout = new javax.swing.GroupLayout(jDialogMiseEnPlace.getContentPane());
        jDialogMiseEnPlace.getContentPane().setLayout(jDialogMiseEnPlaceLayout);
        jDialogMiseEnPlaceLayout.setHorizontalGroup(
            jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                        .addComponent(jPanel14, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap())
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jDialogMiseEnPlaceLayout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jButton12)
                        .addGap(18, 18, 18)
                        .addComponent(jButton13, javax.swing.GroupLayout.PREFERRED_SIZE, 147, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(267, 267, 267))))
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jDialogMiseEnPlaceLayout.createSequentialGroup()
                .addContainerGap(69, Short.MAX_VALUE)
                .addComponent(jLabel38, javax.swing.GroupLayout.PREFERRED_SIZE, 719, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(74, 74, 74))
        );
        jDialogMiseEnPlaceLayout.setVerticalGroup(
            jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel14, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jLabel38, javax.swing.GroupLayout.PREFERRED_SIZE, 118, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(29, 29, 29)
                .addGroup(jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton12, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton13, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(19, Short.MAX_VALUE))
        );

        jDialogZeroAxes.setMinimumSize(new java.awt.Dimension(755, 625));
        jDialogZeroAxes.setName("jDialogMiseEnPlace"); // NOI18N

        javax.swing.GroupLayout jPanel25Layout = new javax.swing.GroupLayout(jPanel25);
        jPanel25.setLayout(jPanel25Layout);
        jPanel25Layout.setHorizontalGroup(
            jPanel25Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 733, Short.MAX_VALUE)
        );
        jPanel25Layout.setVerticalGroup(
            jPanel25Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 16, Short.MAX_VALUE)
        );

        jPanelMachineControl1.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Control", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelMachineControl1.setToolTipText("Ce panneau de controle permets de déplacer la CNC en temps réel. Il est possible d'ajuster le pas avec la glissière.");

        jRadioButtonInches1.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonInches1.setText("inch");
        jRadioButtonInches1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonInches1ActionPerformed(evt);
            }
        });

        jRadioButtonMillimeters1.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonMillimeters1.setSelected(true);
        jRadioButtonMillimeters1.setText("mm");
        jRadioButtonMillimeters1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonMillimeters1ActionPerformed(evt);
            }
        });

        jLabel42.setForeground(new java.awt.Color(255, 255, 255));
        jLabel42.setText("Step Size:");

        jSpinnerStep1.setModel(new javax.swing.SpinnerNumberModel(1.0d, 0.009999999776482582d, null, 0.009999999776482582d));
        jSpinnerStep1.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));

        jButtonYMinus1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonYMinus1.setText("Y-");
        jButtonYMinus1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonYMinus1ActionPerformed(evt);
            }
        });

        jButtonXMinus1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonXMinus1.setText("X-");
        jButtonXMinus1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonXMinus1ActionPerformed(evt);
            }
        });

        jButtonYPlus1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonYPlus1.setText("Y+");
        jButtonYPlus1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonYPlus1ActionPerformed(evt);
            }
        });

        jButtonXPlus1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonXPlus1.setText("X+");
        jButtonXPlus1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonXPlus1ActionPerformed(evt);
            }
        });

        jButtonZPlus1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonZPlus1.setText("Z+");
        jButtonZPlus1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonZPlus1ActionPerformed(evt);
            }
        });

        jButtonZMinus1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonZMinus1.setText("Z-");
        jButtonZMinus1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonZMinus1ActionPerformed(evt);
            }
        });

        jCheckBoxEnableKeyboardJogging1.setSelected(true);
        jCheckBoxEnableKeyboardJogging1.setText("Enable Keyboard Jogging");
        jCheckBoxEnableKeyboardJogging1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxEnableKeyboardJogging1ActionPerformed(evt);
            }
        });

        jLabelRemoveFocus1.setFont(new java.awt.Font("Tahoma", 0, 9)); // NOI18N
        jLabelRemoveFocus1.setForeground(new java.awt.Color(255, 255, 255));
        jLabelRemoveFocus1.setText("[Click To Focus]");
        jLabelRemoveFocus1.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        jLabelRemoveFocus1.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelRemoveFocus1MouseClicked(evt);
            }
        });

        jButtonReturnToZero1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonReturnToZero1.setForeground(new java.awt.Color(255, 255, 255));
        jButtonReturnToZero1.setText("Return to Ø");
        jButtonReturnToZero1.setToolTipText("Return to initial Work Position (0,0,0)");
        jButtonReturnToZero1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonReturnToZero1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanelJogButtons1Layout = new javax.swing.GroupLayout(jPanelJogButtons1);
        jPanelJogButtons1.setLayout(jPanelJogButtons1Layout);
        jPanelJogButtons1Layout.setHorizontalGroup(
            jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                .addComponent(jButtonXMinus1, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addGroup(jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonYPlus1, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonYMinus1, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButtonXPlus1, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonZPlus1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonZMinus1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(0, 0, Short.MAX_VALUE))
            .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jCheckBoxEnableKeyboardJogging1)
                        .addGap(18, 18, 18)
                        .addComponent(jLabelRemoveFocus1))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanelJogButtons1Layout.createSequentialGroup()
                        .addComponent(jButtonReturnToZero1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap())))
        );
        jPanelJogButtons1Layout.setVerticalGroup(
            jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanelJogButtons1Layout.createSequentialGroup()
                            .addGap(21, 21, 21)
                            .addComponent(jButtonXPlus1, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGap(23, 23, 23))
                        .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                            .addComponent(jButtonYPlus1, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(jButtonYMinus1, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                        .addGap(21, 21, 21)
                        .addComponent(jButtonXMinus1, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanelJogButtons1Layout.createSequentialGroup()
                        .addComponent(jButtonZPlus1, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonZMinus1, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 11, Short.MAX_VALUE)
                .addGroup(jPanelJogButtons1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jCheckBoxEnableKeyboardJogging1)
                    .addComponent(jLabelRemoveFocus1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jButtonReturnToZero1, javax.swing.GroupLayout.PREFERRED_SIZE, 32, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        jSliderStepSize1.setMaximum(5);
        jSliderStepSize1.setMinorTickSpacing(1);
        jSliderStepSize1.setPaintLabels(true);
        jSliderStepSize1.setPaintTicks(true);
        jSliderStepSize1.setSnapToTicks(true);
        jSliderStepSize1.setValue(3);
        jSliderStepSize1.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jSliderStepSize1StateChanged(evt);
            }
        });

        javax.swing.GroupLayout jPanelMachineControl1Layout = new javax.swing.GroupLayout(jPanelMachineControl1);
        jPanelMachineControl1.setLayout(jPanelMachineControl1Layout);
        jPanelMachineControl1Layout.setHorizontalGroup(
            jPanelMachineControl1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMachineControl1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMachineControl1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSliderStepSize1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanelJogButtons1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(jPanelMachineControl1Layout.createSequentialGroup()
                        .addComponent(jLabel42)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jSpinnerStep1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jRadioButtonInches1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jRadioButtonMillimeters1)
                        .addGap(0, 0, Short.MAX_VALUE))))
        );
        jPanelMachineControl1Layout.setVerticalGroup(
            jPanelMachineControl1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMachineControl1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMachineControl1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel42)
                    .addComponent(jSpinnerStep1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jRadioButtonInches1)
                    .addComponent(jRadioButtonMillimeters1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jSliderStepSize1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jPanelJogButtons1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanelConnection2.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Connection", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelConnection2.setToolTipText("Cette interface permets de vérifier l'état de la connection avec la CNC. \\n Lorsqu'une erreur intervient, la CNC se mets en état \"Arlarm\". Pour continuer ou recommencer la découpe, cliquez sur \"Kill Alarm\", puis \"Soft Reset\". \\n En cas d'erreur de connection, cliquez sur \"Disconnect\" et changez le paramètres de connection dans System --> GRBL Settings");
        jPanelConnection2.setVerifyInputWhenFocusTarget(false);
        jPanelConnection2.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonSoftReset3.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonSoftReset3.setText("Soft Reset");
        jButtonSoftReset3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonSoftReset3ActionPerformed(evt);
            }
        });
        jPanelConnection2.add(jButtonSoftReset3, new org.netbeans.lib.awtextra.AbsoluteConstraints(110, 60, -1, -1));

        jLabelMachineX3.setText("Status:");
        jPanelConnection2.add(jLabelMachineX3, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 20, 40, 20));

        jButtonKillAlarm4.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonKillAlarm4.setText("Kill Alarm");
        jButtonKillAlarm4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonKillAlarm4ActionPerformed(evt);
            }
        });
        jPanelConnection2.add(jButtonKillAlarm4, new org.netbeans.lib.awtextra.AbsoluteConstraints(170, 20, -1, -1));

        jLabelActiveState2.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jLabelActiveState2.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelActiveState2.setText("Restarting...");
        jLabelActiveState2.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                jLabelActiveState2PropertyChange(evt);
            }
        });
        jPanelConnection2.add(jLabelActiveState2, new org.netbeans.lib.awtextra.AbsoluteConstraints(70, 20, 120, 20));

        jButtonConnectDisconnect3.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonConnectDisconnect3.setText("Disconnect");
        jButtonConnectDisconnect3.setName("jButtonConnectDisconnect"); // NOI18N
        jButtonConnectDisconnect3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonConnectDisconnect3ActionPerformed(evt);
            }
        });
        jPanelConnection2.add(jButtonConnectDisconnect3, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 60, -1, -1));

        jPanelConnection4.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "En Cas D'Erreur", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelConnection4.setToolTipText("Cette interface permets de vérifier l'état de la connection avec la CNC. \\n Lorsqu'une erreur intervient, la CNC se mets en état \"Arlarm\". Pour continuer ou recommencer la découpe, cliquez sur \"Kill Alarm\", puis \"Soft Reset\". \\n En cas d'erreur de connection, cliquez sur \"Disconnect\" et changez le paramètres de connection dans System --> GRBL Settings");
        jPanelConnection4.setVerifyInputWhenFocusTarget(false);
        jPanelConnection4.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jPanelConnection4.add(jLabel1, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 30, 240, 120));


        javax.swing.GroupLayout jPanel31Layout = new javax.swing.GroupLayout(jPanel31);
        jPanel31.setLayout(jPanel31Layout);
        jPanel31Layout.setHorizontalGroup(
            jPanel31Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel31Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanelMachineControl1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(jPanel31Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(jPanelConnection2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanelConnection4, javax.swing.GroupLayout.DEFAULT_SIZE, 264, Short.MAX_VALUE))
                .addContainerGap())
        );
        jPanel31Layout.setVerticalGroup(
            jPanel31Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel31Layout.createSequentialGroup()
                .addContainerGap(31, Short.MAX_VALUE)
                .addGroup(jPanel31Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(jPanel31Layout.createSequentialGroup()
                        .addComponent(jPanelConnection2, javax.swing.GroupLayout.PREFERRED_SIZE, 97, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jPanelConnection4, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addComponent(jPanelMachineControl1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        javax.swing.GroupLayout jPanel24Layout = new javax.swing.GroupLayout(jPanel24);
        jPanel24.setLayout(jPanel24Layout);
        jPanel24Layout.setHorizontalGroup(
            jPanel24Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel24Layout.createSequentialGroup()
                .addComponent(jPanel25, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
            .addGroup(jPanel24Layout.createSequentialGroup()
                .addGap(102, 102, 102)
                .addComponent(jPanel31, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel24Layout.setVerticalGroup(
            jPanel24Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel24Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel31, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel25, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jLabel39.setText("<html>Maintenant, il faut positionner la CNC a son zéro de référence afin qu'elle puisse savoir sa position réelle.  \n<br><br> \nCela est fait en cliquant dans le menu <i>Machine</i> puis <i> Start Homing Sequence </i> ou sur le bouton ci dessous <br>\nApres la mise a zéro des axes X et Y, l'axe Z est référencer avec le bouton suivant ou dans <i>Tools</i> puis <i>Probe CNC </i> ");

        jButton14.setText("Position de Référence");
        jButton14.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton14ActionPerformed(evt);
            }
        });

        jButton24.setText("Suivant");
        jButton24.setEnabled(false);
        jButton24.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton24ActionPerformed(evt);
            }
        });

        jButton25.setText("Capteur Référence Z");
        jButton25.setEnabled(false);
        jButton25.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton25ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogZeroAxesLayout = new javax.swing.GroupLayout(jDialogZeroAxes.getContentPane());
        jDialogZeroAxes.getContentPane().setLayout(jDialogZeroAxesLayout);
        jDialogZeroAxesLayout.setHorizontalGroup(
            jDialogZeroAxesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogZeroAxesLayout.createSequentialGroup()
                .addGroup(jDialogZeroAxesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel24, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(jDialogZeroAxesLayout.createSequentialGroup()
                        .addGap(93, 93, 93)
                        .addGroup(jDialogZeroAxesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel39, javax.swing.GroupLayout.PREFERRED_SIZE, 543, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(jDialogZeroAxesLayout.createSequentialGroup()
                                .addGap(47, 47, 47)
                                .addComponent(jButton14)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jButton25, javax.swing.GroupLayout.PREFERRED_SIZE, 147, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jButton24, javax.swing.GroupLayout.PREFERRED_SIZE, 147, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 103, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jDialogZeroAxesLayout.setVerticalGroup(
            jDialogZeroAxesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogZeroAxesLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel24, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jLabel39, javax.swing.GroupLayout.PREFERRED_SIZE, 118, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jDialogZeroAxesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogZeroAxesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(jButton24, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(jButton25, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jButton14, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jDialogImportGCode.setMinimumSize(new java.awt.Dimension(624, 558));
        jDialogImportGCode.setName("jDialogMiseEnPlace"); // NOI18N

        jLabel40.setText("<html> <h3> Sélectionnez le fichier que vous souhaitez  découper dans le pannel ci dessous. </h3> </html>");

        javax.swing.GroupLayout jPanel28Layout = new javax.swing.GroupLayout(jPanel28);
        jPanel28.setLayout(jPanel28Layout);
        jPanel28Layout.setHorizontalGroup(
            jPanel28Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel28Layout.createSequentialGroup()
                .addContainerGap(80, Short.MAX_VALUE)
                .addComponent(jLabel40, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(81, Short.MAX_VALUE))
        );
        jPanel28Layout.setVerticalGroup(
            jPanel28Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel28Layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel40, javax.swing.GroupLayout.PREFERRED_SIZE, 86, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32))
        );

        jLabel40.getAccessibleContext().setAccessibleName("Sélectionnez le fichier que vous souhaitez  découper dans le pannel ci dessous. ");

        javax.swing.GroupLayout jPanel26Layout = new javax.swing.GroupLayout(jPanel26);
        jPanel26.setLayout(jPanel26Layout);
        jPanel26Layout.setHorizontalGroup(
            jPanel26Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel26Layout.createSequentialGroup()
                .addComponent(jPanel28, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );
        jPanel26Layout.setVerticalGroup(
            jPanel26Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel26Layout.createSequentialGroup()
                .addComponent(jPanel28, javax.swing.GroupLayout.PREFERRED_SIZE, 108, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 31, Short.MAX_VALUE))
        );

        jButton27.setText("Suivant");
        jButton27.setEnabled(false);
        jButton27.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton27ActionPerformed(evt);
            }
        });

        jPanel29.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "G-Code File", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel29.setToolTipText("Ce panneau permets d'importer et visualiser les détails de découpe du fichier. ");
        jPanel29.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jLabelRowsInFile9.setText("0");
        jPanel29.add(jLabelRowsInFile9, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 90, 54, -1));

        jLabelRowsInFile10.setText("Sent Rows:");
        jPanel29.add(jLabelRowsInFile10, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 110, 80, -1));

        jLabelRowsInFile20.setText("Remaining Rows:");
        jPanel29.add(jLabelRowsInFile20, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 100, -1));

        jLabelRowsInFile21.setText("Rows in file:");
        jPanel29.add(jLabelRowsInFile21, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 80, -1));

        jLabelSentRows2.setText("0");
        jPanel29.add(jLabelSentRows2, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 110, 54, -1));

        jLabelRemainingRows2.setText("0");
        jPanel29.add(jLabelRemainingRows2, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 130, 54, -1));

        jButtonGCodeBrowse2.setBackground(new java.awt.Color(255, 0, 255));
        jButtonGCodeBrowse2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeBrowse2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeBrowse2.setText("Browse");
        jButtonGCodeBrowse2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeBrowse2ActionPerformed(evt);
            }
        });

        jTextFieldGCodeFile2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextFieldGCodeFile2ActionPerformed(evt);
            }
        });

        jLabel41.setText("File:");

        jButtonGCodePause2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodePause2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodePause2.setText("Pause");
        jButtonGCodePause2.setEnabled(false);
        jButtonGCodePause2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodePause2ActionPerformed(evt);
            }
        });

        jButtonGCodeSend2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeSend2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeSend2.setText("Send");
        jButtonGCodeSend2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeSend2ActionPerformed(evt);
            }
        });

        jButtonGCodeCancel2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeCancel2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeCancel2.setText("Cancel");
        jButtonGCodeCancel2.setEnabled(false);
        jButtonGCodeCancel2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeCancel2ActionPerformed(evt);
            }
        });

        jButtonGCodeVisualize2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeVisualize2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeVisualize2.setText("Visualize");
        jButtonGCodeVisualize2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeVisualize2ActionPerformed(evt);
            }
        });

        jButtonBrowse1.setText("Browse");
        jButtonBrowse1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonBrowse1ActionPerformed(evt);
            }
        });

        jButtonVisualise1.setText("Visualize");
        jButtonVisualise1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonVisualise1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanelGCodeFile2Layout = new javax.swing.GroupLayout(jPanelGCodeFile2);
        jPanelGCodeFile2.setLayout(jPanelGCodeFile2Layout);
        jPanelGCodeFile2Layout.setHorizontalGroup(
            jPanelGCodeFile2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFile2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFile2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelGCodeFile2Layout.createSequentialGroup()
                        .addComponent(jButtonGCodeSend2, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodePause2, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodeCancel2, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanelGCodeFile2Layout.createSequentialGroup()
                        .addComponent(jLabel41, javax.swing.GroupLayout.PREFERRED_SIZE, 26, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTextFieldGCodeFile2)))
                .addGap(18, 18, 18)
                .addGroup(jPanelGCodeFile2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelGCodeFile2Layout.createSequentialGroup()
                        .addComponent(jButtonVisualise1)
                        .addGap(489, 489, 489)
                        .addComponent(jButtonGCodeVisualize2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodeBrowse2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanelGCodeFile2Layout.createSequentialGroup()
                        .addComponent(jButtonBrowse1)
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanelGCodeFile2Layout.setVerticalGroup(
            jPanelGCodeFile2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFile2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFile2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel41)
                    .addComponent(jTextFieldGCodeFile2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonBrowse1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanelGCodeFile2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonGCodePause2)
                    .addComponent(jButtonGCodeSend2)
                    .addComponent(jButtonGCodeCancel2)
                    .addComponent(jButtonGCodeVisualize2)
                    .addComponent(jButtonGCodeBrowse2)
                    .addComponent(jButtonVisualise1))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanel29.add(jPanelGCodeFile2, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 20, 440, 70));

        javax.swing.GroupLayout jDialogImportGCodeLayout = new javax.swing.GroupLayout(jDialogImportGCode.getContentPane());
        jDialogImportGCode.getContentPane().setLayout(jDialogImportGCodeLayout);
        jDialogImportGCodeLayout.setHorizontalGroup(
            jDialogImportGCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addComponent(jPanel26, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jPanel29, javax.swing.GroupLayout.PREFERRED_SIZE, 495, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addGap(272, 272, 272)
                .addComponent(jButton27, javax.swing.GroupLayout.PREFERRED_SIZE, 147, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialogImportGCodeLayout.setVerticalGroup(
            jDialogImportGCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel26, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel29, javax.swing.GroupLayout.PREFERRED_SIZE, 154, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 86, Short.MAX_VALUE)
                .addComponent(jButton27, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(46, 46, 46))
        );

        jDialogAskTuto.setMinimumSize(new java.awt.Dimension(537, 480));

        jLabel51.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel51.setText("<html> <h1 font-size=\"100\"> Voulez vous suivre le tutoriel du logiciel ? </h1></html>");

        jLabel52.setText("<html> Le tutoriel du logiciel vous guide à travers tous les modules de celui-ci afin de découvrir les bases de ce logiciel.  <br><br> Cliquez sur \"Suivre le tutoriel du logiel\" pour revoir celui-ci. <br> <br> Sinon, vous suivrez un guide sur les dangers et les limitations de la CNC</html>");

        jButton15.setText("<html>Suivre le tutoriel du logiciel</html>");
        jButton15.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton15ActionPerformed(evt);
            }
        });

        jButton16.setText("<html> Non, j'ai déja effectué le tutoriel</html>");
        jButton16.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton16ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jDialogAskTutoLayout = new javax.swing.GroupLayout(jDialogAskTuto.getContentPane());
        jDialogAskTuto.getContentPane().setLayout(jDialogAskTutoLayout);
        jDialogAskTutoLayout.setHorizontalGroup(
            jDialogAskTutoLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator5)
            .addComponent(jSeparator18)
            .addGroup(jDialogAskTutoLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jDialogAskTutoLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogAskTutoLayout.createSequentialGroup()
                        .addGroup(jDialogAskTutoLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel52)
                            .addGroup(jDialogAskTutoLayout.createSequentialGroup()
                                .addComponent(jLabel51, javax.swing.GroupLayout.PREFERRED_SIZE, 508, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(0, 17, Short.MAX_VALUE)))
                        .addContainerGap())
                    .addGroup(jDialogAskTutoLayout.createSequentialGroup()
                        .addGap(13, 13, 13)
                        .addComponent(jButton15, javax.swing.GroupLayout.PREFERRED_SIZE, 219, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(jButton16, javax.swing.GroupLayout.PREFERRED_SIZE, 219, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(19, 19, 19))))
        );
        jDialogAskTutoLayout.setVerticalGroup(
            jDialogAskTutoLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogAskTutoLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel52, javax.swing.GroupLayout.PREFERRED_SIZE, 105, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator5, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel51, javax.swing.GroupLayout.PREFERRED_SIZE, 214, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator18, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialogAskTutoLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton15, javax.swing.GroupLayout.PREFERRED_SIZE, 71, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton16, javax.swing.GroupLayout.PREFERRED_SIZE, 71, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(22, Short.MAX_VALUE))
        );

        JDialogSecuriter.setMaximumSize(new java.awt.Dimension(620, 620));
        JDialogSecuriter.setMinimumSize(new java.awt.Dimension(620, 620));

        jLabel54.setText("<html> <h2 center> Les dangers de la CNC </h2>  <br><br> La CNC est un outil puissant mais potentiellement dangereux. Des vitesses d'avance trop rapides ou des RPM trop faibles peuvent entraîner des accidents graves.   <br><br>Il est important de suivre les procédures de sécurité et de respecter les limites de vitesse et de puissance recommandées.   <br><br> Il est recommandé de porter des équipements de protection tels que des lunettes de protection, et des habits épais ( pas de short ) pour réduire les risques d'accidents.");

        jTableGCodeLog2.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {
                {"Matériel", "Vitesse D'Avance", "Vitesse de Rotation"},
                {null, null, null},
                {"Bois", "2 000-5 000 mm/min", "8 000-240 00 tr/min"},
                {"Plexiglass", "500-1500 mm/min", "15000-24 000 tr/min"},
                {"Cuivre", "200-400 mm/min", "1 000-3 000 tr/min"},
                {"Acier Doux", "200-400 mm/min", "800-1 200 tr/min"},
                {"Aluminium", "1 000-3 000 mm/min", "5 000-15 000 tr/min"}
            },
            new String [] {
                "Matériel", "Vitesse d'Avancement", "Vitesse génériques minimum"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class, java.lang.String.class
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }
        });
        jTableGCodeLog2.getTableHeader().setReorderingAllowed(false);
        jTableGCodeLog2.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jTableGCodeLog2MouseMoved(evt);
            }
        });
        jTableGCodeLog2.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                jTableGCodeLog2MouseEntered(evt);
            }
        });

        jButtonOk7.setText("Suivant");
        jButtonOk7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk7ActionPerformed(evt);
            }
        });

        jButtonCancel7.setText("Quitter");
        jButtonCancel7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel7ActionPerformed(evt);
            }
        });

        jLabel58.setText("<html> <h1 center> Attention </h1> il est important de noter que ces vitesses peuvent varier en fonction de nombreux facteurs, tels que la géométrie de l'outil de coupe, la profondeur de coupe, la qualité de la surface désirée, etc. Par conséquent, il est recommandé de consulter les spécifications du fabricant de la machine-outil et/ou de l'outil de coupe pour des informations plus précises.</html>");

        javax.swing.GroupLayout JDialogSecuriterLayout = new javax.swing.GroupLayout(JDialogSecuriter.getContentPane());
        JDialogSecuriter.getContentPane().setLayout(JDialogSecuriterLayout);
        JDialogSecuriterLayout.setHorizontalGroup(
            JDialogSecuriterLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, JDialogSecuriterLayout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(jButtonOk7, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel7, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(JDialogSecuriterLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(JDialogSecuriterLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel58, javax.swing.GroupLayout.PREFERRED_SIZE, 582, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jTableGCodeLog2, javax.swing.GroupLayout.PREFERRED_SIZE, 591, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel54, javax.swing.GroupLayout.PREFERRED_SIZE, 585, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(22, Short.MAX_VALUE))
        );
        JDialogSecuriterLayout.setVerticalGroup(
            JDialogSecuriterLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(JDialogSecuriterLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel54, javax.swing.GroupLayout.PREFERRED_SIZE, 271, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jTableGCodeLog2, javax.swing.GroupLayout.PREFERRED_SIZE, 140, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jLabel58, javax.swing.GroupLayout.PREFERRED_SIZE, 142, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 41, Short.MAX_VALUE)
                .addGroup(JDialogSecuriterLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk7)
                    .addComponent(jButtonCancel7))
                .addGap(23, 23, 23))
        );

        if (jTableGCodeLog2.getColumnModel().getColumnCount() > 0) {
            jTableGCodeLog2.getColumnModel().getColumn(0).setResizable(false);
        }

        jDialogMachineAxis.setMinimumSize(new java.awt.Dimension(612, 650));
        jDialogMachineAxis.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        jDialogMachineAxis.setModalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL);

        jLabel53.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel53.setText("<html> <h3 text-decoration=\"bold\"> Interface de Connection");
        jLabel53.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel57.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel57.setText("<html> <h4><center>Cette interface permets de controler  laposition de la CNC </center></h4>   Entrez un pas manuellement, ou sélectionnez le avec le slider. En appuyant sur un des bouttons, la CNC se déplaceras de cette quantité.  Vous avez la possibilité d'activer le jogging par clabier pour controler la CNC avec les fleches de votre clavier. Cette option n'est pas recomandée au débutants");
        jLabel57.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jButtonCancel6.setText("Quitter");
        jButtonCancel6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel6ActionPerformed(evt);
            }
        });

        jButtonOk6.setText("Suivant");
        jButtonOk6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk6ActionPerformed(evt);
            }
        });

        jPanelMachineControl2.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Control", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelMachineControl2.setToolTipText("");
        jPanelMachineControl2.setMinimumSize(new java.awt.Dimension(280, 272));
        jPanelMachineControl2.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jPanelMachineControl2MouseMoved(evt);
            }
        });

        jRadioButtonInches2.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonInches2.setText("inch");
        jRadioButtonInches2.setToolTipText("Définir l'unitée de mesure en inch");
        jRadioButtonInches2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonInches2ActionPerformed(evt);
            }
        });

        jRadioButtonMillimeters2.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonMillimeters2.setSelected(true);
        jRadioButtonMillimeters2.setText("mm");
        jRadioButtonMillimeters2.setToolTipText("Définir l'unitée de mesure en mm");
        jRadioButtonMillimeters2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonMillimeters2ActionPerformed(evt);
            }
        });

        jLabel65.setForeground(new java.awt.Color(255, 255, 255));
        jLabel65.setText("Step Size:");

        jSpinnerStep2.setModel(new javax.swing.SpinnerNumberModel(1.0d, 0.009999999776482582d, null, 0.009999999776482582d));
        jSpinnerStep2.setToolTipText("Pas d'avancement");
        jSpinnerStep2.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));

        jButtonYMinus2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonYMinus2.setText("Y-");
        jButtonYMinus2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonYMinus2ActionPerformed(evt);
            }
        });

        jButtonXMinus2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonXMinus2.setText("X-");
        jButtonXMinus2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonXMinus2ActionPerformed(evt);
            }
        });

        jButtonYPlus2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonYPlus2.setText("Y+");
        jButtonYPlus2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonYPlus2ActionPerformed(evt);
            }
        });

        jButtonXPlus2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonXPlus2.setText("X+");
        jButtonXPlus2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonXPlus2ActionPerformed(evt);
            }
        });

        jButtonZPlus2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonZPlus2.setText("Z+");
        jButtonZPlus2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonZPlus2ActionPerformed(evt);
            }
        });

        jButtonZMinus2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonZMinus2.setText("Z-");
        jButtonZMinus2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonZMinus2ActionPerformed(evt);
            }
        });

        jCheckBoxEnableKeyboardJogging2.setSelected(true);
        jCheckBoxEnableKeyboardJogging2.setText("Activer le jogging par clavier");
        jCheckBoxEnableKeyboardJogging2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxEnableKeyboardJogging2ActionPerformed(evt);
            }
        });

        jLabelRemoveFocus2.setFont(new java.awt.Font("Tahoma", 0, 9)); // NOI18N
        jLabelRemoveFocus2.setForeground(new java.awt.Color(255, 255, 255));
        jLabelRemoveFocus2.setText("[Click To Focus]");
        jLabelRemoveFocus2.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        jLabelRemoveFocus2.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelRemoveFocus2MouseClicked(evt);
            }
        });

        jButtonReturnToZero2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonReturnToZero2.setForeground(new java.awt.Color(255, 255, 255));
        jButtonReturnToZero2.setText("Return to Ø");
        jButtonReturnToZero2.setToolTipText("Return to initial Work Position (0,0,0)");
        jButtonReturnToZero2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonReturnToZero2ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanelJogButtons2Layout = new javax.swing.GroupLayout(jPanelJogButtons2);
        jPanelJogButtons2.setLayout(jPanelJogButtons2Layout);
        jPanelJogButtons2Layout.setHorizontalGroup(
            jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                .addComponent(jButtonXMinus2, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addGroup(jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonYPlus2, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonYMinus2, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButtonXPlus2, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonZPlus2, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonZMinus2, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(0, 0, Short.MAX_VALUE))
            .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jCheckBoxEnableKeyboardJogging2)
                        .addGap(18, 18, 18)
                        .addComponent(jLabelRemoveFocus2))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanelJogButtons2Layout.createSequentialGroup()
                        .addComponent(jButtonReturnToZero2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap())))
        );
        jPanelJogButtons2Layout.setVerticalGroup(
            jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanelJogButtons2Layout.createSequentialGroup()
                            .addGap(21, 21, 21)
                            .addComponent(jButtonXPlus2, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGap(23, 23, 23))
                        .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                            .addComponent(jButtonYPlus2, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(jButtonYMinus2, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                        .addGap(21, 21, 21)
                        .addComponent(jButtonXMinus2, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanelJogButtons2Layout.createSequentialGroup()
                        .addComponent(jButtonZPlus2, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonZMinus2, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 11, Short.MAX_VALUE)
                .addGroup(jPanelJogButtons2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jCheckBoxEnableKeyboardJogging2)
                    .addComponent(jLabelRemoveFocus2))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jButtonReturnToZero2, javax.swing.GroupLayout.PREFERRED_SIZE, 32, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        jSliderStepSize2.setMaximum(5);
        jSliderStepSize2.setMinorTickSpacing(1);
        jSliderStepSize2.setPaintLabels(true);
        jSliderStepSize2.setPaintTicks(true);
        jSliderStepSize2.setSnapToTicks(true);
        jSliderStepSize2.setToolTipText("");
        jSliderStepSize2.setValue(3);
        jSliderStepSize2.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jSliderStepSize2StateChanged(evt);
            }
        });

        javax.swing.GroupLayout jPanel20Layout = new javax.swing.GroupLayout(jPanel20);
        jPanel20.setLayout(jPanel20Layout);
        jPanel20Layout.setHorizontalGroup(
            jPanel20Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 256, Short.MAX_VALUE)
        );
        jPanel20Layout.setVerticalGroup(
            jPanel20Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 16, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout jPanelMachineControl2Layout = new javax.swing.GroupLayout(jPanelMachineControl2);
        jPanelMachineControl2.setLayout(jPanelMachineControl2Layout);
        jPanelMachineControl2Layout.setHorizontalGroup(
            jPanelMachineControl2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMachineControl2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMachineControl2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSliderStepSize2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanelJogButtons2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(jPanelMachineControl2Layout.createSequentialGroup()
                        .addGroup(jPanelMachineControl2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jPanel20, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(jPanelMachineControl2Layout.createSequentialGroup()
                                .addComponent(jLabel65)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jSpinnerStep2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jRadioButtonInches2)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jRadioButtonMillimeters2)))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanelMachineControl2Layout.setVerticalGroup(
            jPanelMachineControl2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMachineControl2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMachineControl2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel65)
                    .addComponent(jSpinnerStep2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jRadioButtonInches2)
                    .addComponent(jRadioButtonMillimeters2))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jSliderStepSize2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jPanelJogButtons2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel20, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(49, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout jDialogMachineAxisLayout = new javax.swing.GroupLayout(jDialogMachineAxis.getContentPane());
        jDialogMachineAxis.getContentPane().setLayout(jDialogMachineAxisLayout);
        jDialogMachineAxisLayout.setHorizontalGroup(
            jDialogMachineAxisLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator20)
            .addGroup(jDialogMachineAxisLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jDialogMachineAxisLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel57, javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jSeparator21)
                    .addGroup(jDialogMachineAxisLayout.createSequentialGroup()
                        .addGap(15, 15, 15)
                        .addComponent(jLabel53, javax.swing.GroupLayout.DEFAULT_SIZE, 585, Short.MAX_VALUE)))
                .addContainerGap())
            .addGroup(jDialogMachineAxisLayout.createSequentialGroup()
                .addGroup(jDialogMachineAxisLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogMachineAxisLayout.createSequentialGroup()
                        .addGap(206, 206, 206)
                        .addComponent(jButtonOk6, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(32, 32, 32)
                        .addComponent(jButtonCancel6, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jDialogMachineAxisLayout.createSequentialGroup()
                        .addGap(166, 166, 166)
                        .addComponent(jPanelMachineControl2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(0, 0, Short.MAX_VALUE))
        );
        jDialogMachineAxisLayout.setVerticalGroup(
            jDialogMachineAxisLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogMachineAxisLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel57, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator20, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanelMachineControl2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(15, 15, 15)
                .addComponent(jSeparator21, javax.swing.GroupLayout.PREFERRED_SIZE, 11, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jLabel53, javax.swing.GroupLayout.PREFERRED_SIZE, 105, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 28, Short.MAX_VALUE)
                .addGroup(jDialogMachineAxisLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk6)
                    .addComponent(jButtonCancel6))
                .addContainerGap(17, Short.MAX_VALUE))
        );

        JDialogLimitations.setMaximumSize(new java.awt.Dimension(620, 620));
        JDialogLimitations.setMinimumSize(new java.awt.Dimension(620, 620));
        JDialogLimitations.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseDragged(java.awt.event.MouseEvent evt) {
                JDialogLimitationsMouseDragged(evt);
            }
        });

        jLabel59.setText("<html> <h2 center> Les dangers de la CNC </h2>  <br><br> La CNC est un outil puissant mais potentiellement dangereux. Des vitesses d'avance trop rapides ou des RPM trop faibles peuvent entraîner des accidents graves.   <br><br>Il est important de suivre les procédures de sécurité et de respecter les limites de vitesse et de puissance recommandées.   <br><br> Il est recommandé de porter des équipements de protection tels que des lunettes de protection, et des habits épais ( pas de short ) pour réduire les risques d'accidents.");

        jTableGCodeLog3.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {
                {"Matériel", "Vitesse D'Avance", "Vitesse de Rotation"},
                {null, null, null},
                {"Bois", "2 000-5 000 mm/min", "8 000-240 00 tr/min"},
                {"Plexiglass", "500-1500 mm/min", "15000-24 000 tr/min"},
                {"Cuivre", "200-400 mm/min", "1 000-3 000 tr/min"},
                {"Acier Doux", "200-400 mm/min", "800-1 200 tr/min"},
                {"Aluminium", "1 000-3 000 mm/min", "5 000-15 000 tr/min"}
            },
            new String [] {
                "Matériel", "Vitesse d'Avancement", "Vitesse génériques minimum"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.String.class, java.lang.String.class
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }
        });
        jTableGCodeLog3.getTableHeader().setReorderingAllowed(false);
        jTableGCodeLog3.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jTableGCodeLog3MouseMoved(evt);
            }
        });
        jTableGCodeLog3.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                jTableGCodeLog3MouseEntered(evt);
            }
        });

        jButtonOk8.setText("Suivant");
        jButtonOk8.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonOk8ActionPerformed(evt);
            }
        });

        jButtonCancel8.setText("Quitter");
        jButtonCancel8.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonCancel8ActionPerformed(evt);
            }
        });

        jLabel60.setText("<html> <h1 center> Attention </h1> il est important de noter que ces vitesses peuvent varier en fonction de nombreux facteurs, tels que la géométrie de l'outil de coupe, la profondeur de coupe, la qualité de la surface désirée, etc. Par conséquent, il est recommandé de consulter les spécifications du fabricant de la machine-outil et/ou de l'outil de coupe pour des informations plus précises.</html>");

        javax.swing.GroupLayout JDialogLimitationsLayout = new javax.swing.GroupLayout(JDialogLimitations.getContentPane());
        JDialogLimitations.getContentPane().setLayout(JDialogLimitationsLayout);
        JDialogLimitationsLayout.setHorizontalGroup(
            JDialogLimitationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, JDialogLimitationsLayout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(jButtonOk8, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel8, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(JDialogLimitationsLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(JDialogLimitationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel59, javax.swing.GroupLayout.PREFERRED_SIZE, 582, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jTableGCodeLog3, javax.swing.GroupLayout.PREFERRED_SIZE, 591, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel60, javax.swing.GroupLayout.PREFERRED_SIZE, 585, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(22, Short.MAX_VALUE))
        );
        JDialogLimitationsLayout.setVerticalGroup(
            JDialogLimitationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(JDialogLimitationsLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel60, javax.swing.GroupLayout.PREFERRED_SIZE, 271, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jTableGCodeLog3, javax.swing.GroupLayout.PREFERRED_SIZE, 140, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jLabel59, javax.swing.GroupLayout.PREFERRED_SIZE, 142, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 41, Short.MAX_VALUE)
                .addGroup(JDialogLimitationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk8)
                    .addComponent(jButtonCancel8))
                .addGap(23, 23, 23))
        );

        if (jTableGCodeLog3.getColumnModel().getColumnCount() > 0) {
            jTableGCodeLog3.getColumnModel().getColumn(0).setResizable(false);
        }

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("SourceRabbit GCODE Sender");
        setSize(new java.awt.Dimension(1000, 728));
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseDragged(java.awt.event.MouseEvent evt) {
                formMouseDragged(evt);
            }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent evt) {
                formComponentResized(evt);
            }
        });
        addWindowFocusListener(new java.awt.event.WindowFocusListener() {
            public void windowGainedFocus(java.awt.event.WindowEvent evt) {
                formWindowGainedFocus(evt);
            }
            public void windowLostFocus(java.awt.event.WindowEvent evt) {
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowActivated(java.awt.event.WindowEvent evt) {
                formWindowActivated(evt);
            }
            public void windowOpened(java.awt.event.WindowEvent evt) {
                formWindowOpened(evt);
            }
        });

        jPanel1.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "G-Code File", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel1.setToolTipText("");
        jPanel1.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jPanel1MouseMoved(evt);
            }
        });
        jPanel1.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jLabelRowsInFile.setText("0");
        jPanel1.add(jLabelRowsInFile, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 90, 54, -1));

        jLabelRowsInFile1.setText("Lignes Envoyées:");
        jPanel1.add(jLabelRowsInFile1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 110, 100, -1));

        jLabelRowsInFile2.setText("Lignes Restantes:");
        jPanel1.add(jLabelRowsInFile2, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 100, -1));

        jLabelRowsInFile3.setText("Ligne du Fichier:");
        jPanel1.add(jLabelRowsInFile3, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 90, -1));

        jLabelSentRows.setText("0");
        jPanel1.add(jLabelSentRows, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 110, 54, -1));

        jLabelRemainingRows.setText("0");
        jPanel1.add(jLabelRemainingRows, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 130, 54, -1));

        jLabelRowsInFile4.setText("Temps écoulé:");
        jPanel1.add(jLabelRowsInFile4, new org.netbeans.lib.awtextra.AbsoluteConstraints(210, 90, 80, -1));

        jLabelTimeElapsed.setText("00:00:00");
        jPanel1.add(jLabelTimeElapsed, new org.netbeans.lib.awtextra.AbsoluteConstraints(300, 90, 146, -1));

        jLabelRowsInFile5.setText("Avancement :");
        jPanel1.add(jLabelRowsInFile5, new org.netbeans.lib.awtextra.AbsoluteConstraints(210, 110, 80, -1));

        jProgressBarGCodeProgress.setPreferredSize(new java.awt.Dimension(146, 16));
        jPanel1.add(jProgressBarGCodeProgress, new org.netbeans.lib.awtextra.AbsoluteConstraints(300, 110, 230, -1));

        jButtonGCodeBrowse.setBackground(new java.awt.Color(255, 0, 255));
        jButtonGCodeBrowse.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeBrowse.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeBrowse.setText("Browse");
        jButtonGCodeBrowse.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeBrowseActionPerformed(evt);
            }
        });

        jTextFieldGCodeFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextFieldGCodeFileActionPerformed(evt);
            }
        });

        jLabel5.setText("Fichier:");

        jButtonGCodePause.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodePause.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodePause.setText("Pause");
        jButtonGCodePause.setEnabled(false);
        jButtonGCodePause.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonGCodePauseMouseMoved(evt);
            }
        });
        jButtonGCodePause.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodePauseActionPerformed(evt);
            }
        });

        jButtonGCodeSend.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeSend.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeSend.setText("Send");
        jButtonGCodeSend.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonGCodeSendMouseMoved(evt);
            }
        });
        jButtonGCodeSend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeSendActionPerformed(evt);
            }
        });

        jButtonGCodeCancel.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeCancel.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeCancel.setText("Cancel");
        jButtonGCodeCancel.setEnabled(false);
        jButtonGCodeCancel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonGCodeCancelMouseMoved(evt);
            }
        });
        jButtonGCodeCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeCancelActionPerformed(evt);
            }
        });

        jButtonGCodeVisualize.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeVisualize.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeVisualize.setText("Visualize");
        jButtonGCodeVisualize.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeVisualizeActionPerformed(evt);
            }
        });

        jButtonBrowse.setText("Sélectionner");
        jButtonBrowse.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonBrowseMouseMoved(evt);
            }
        });
        jButtonBrowse.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonBrowseActionPerformed(evt);
            }
        });

        jButtonVisualise.setText("Visualiser");
        jButtonVisualise.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonVisualiseMouseMoved(evt);
            }
        });
        jButtonVisualise.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonVisualiseActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanelGCodeFileLayout = new javax.swing.GroupLayout(jPanelGCodeFile);
        jPanelGCodeFile.setLayout(jPanelGCodeFileLayout);
        jPanelGCodeFileLayout.setHorizontalGroup(
            jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                        .addComponent(jButtonGCodeSend, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodePause, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodeCancel, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                        .addComponent(jLabel5, javax.swing.GroupLayout.PREFERRED_SIZE, 46, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTextFieldGCodeFile)))
                .addGap(18, 18, 18)
                .addGroup(jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                        .addComponent(jButtonVisualise)
                        .addGap(489, 489, 489)
                        .addComponent(jButtonGCodeVisualize)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonGCodeBrowse, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                        .addComponent(jButtonBrowse)
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanelGCodeFileLayout.setVerticalGroup(
            jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(jTextFieldGCodeFile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonBrowse))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonGCodePause)
                    .addComponent(jButtonGCodeSend)
                    .addComponent(jButtonGCodeCancel)
                    .addComponent(jButtonGCodeVisualize)
                    .addComponent(jButtonGCodeBrowse)
                    .addComponent(jButtonVisualise))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanel1.add(jPanelGCodeFile, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 20, 530, 70));

        jTabbedPane1.setForeground(new java.awt.Color(255, 255, 255));
        jTabbedPane1.setToolTipText("Sur cette interface, l'utilisateur peut envoyer des commandes à la CNC, vérifier l'avancée de la découpe en temps réel et  récupérer les dernières informations. ");
        jTabbedPane1.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jTabbedPane1.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jTabbedPane1MouseMoved(evt);
            }
        });

        jLabel7.setText("Command:");

        jTextFieldCommand.setToolTipText("Send a command to the controller");
        jTextFieldCommand.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextFieldCommandActionPerformed(evt);
            }
        });

        jTextAreaConsole.setEditable(false);
        jTextAreaConsole.setColumns(20);
        jTextAreaConsole.setFont(new java.awt.Font("Tahoma", 0, 14)); // NOI18N
        jTextAreaConsole.setForeground(new java.awt.Color(255, 255, 255));
        jTextAreaConsole.setRows(5);
        jScrollPane2.setViewportView(jTextAreaConsole);

        jButtonClearConsole.setText("Clear Console");
        jButtonClearConsole.setToolTipText("Clear the GCode Log");
        jButtonClearConsole.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonClearConsoleMouseMoved(evt);
            }
        });
        jButtonClearConsole.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonClearConsoleActionPerformed(evt);
            }
        });

        jCheckBoxShowVerboseOutput.setText("Show verbose output");
        jCheckBoxShowVerboseOutput.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxShowVerboseOutputActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel5Layout = new javax.swing.GroupLayout(jPanel5);
        jPanel5.setLayout(jPanel5Layout);
        jPanel5Layout.setHorizontalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addComponent(jLabel7)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTextFieldCommand))
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addComponent(jButtonClearConsole)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jCheckBoxShowVerboseOutput)
                        .addGap(0, 369, Short.MAX_VALUE))
                    .addComponent(jScrollPane2, javax.swing.GroupLayout.Alignment.TRAILING))
                .addContainerGap())
        );
        jPanel5Layout.setVerticalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel7)
                    .addComponent(jTextFieldCommand, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 387, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonClearConsole)
                    .addComponent(jCheckBoxShowVerboseOutput))
                .addGap(34, 34, 34))
        );

        jTabbedPane1.addTab("Console", jPanel5);

        jTableGCodeLog.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Row", "Command", "TX", "RX", "Error"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.Object.class, java.lang.Object.class, java.lang.Boolean.class, java.lang.Boolean.class, java.lang.Object.class
            };
            boolean[] canEdit = new boolean [] {
                true, true, false, false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        jTableGCodeLog.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jTableGCodeLogMouseMoved(evt);
            }
        });
        jTableGCodeLog.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                jTableGCodeLogMouseEntered(evt);
            }
        });
        jScrollPane1.setViewportView(jTableGCodeLog);
        if (jTableGCodeLog.getColumnModel().getColumnCount() > 0) {
            jTableGCodeLog.getColumnModel().getColumn(0).setMinWidth(20);
            jTableGCodeLog.getColumnModel().getColumn(0).setPreferredWidth(20);
            jTableGCodeLog.getColumnModel().getColumn(0).setHeaderValue("Row");
            jTableGCodeLog.getColumnModel().getColumn(1).setHeaderValue("Command");
            jTableGCodeLog.getColumnModel().getColumn(2).setMinWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(2).setPreferredWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(2).setMaxWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(2).setHeaderValue("TX");
            jTableGCodeLog.getColumnModel().getColumn(3).setMinWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(3).setPreferredWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(3).setMaxWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(3).setHeaderValue("RX");
            jTableGCodeLog.getColumnModel().getColumn(4).setResizable(false);
            jTableGCodeLog.getColumnModel().getColumn(4).setHeaderValue("Error");
        }

        jButtonClearLog.setText("Clear Log");
        jButtonClearLog.setToolTipText("Clear the GCode Log");
        jButtonClearLog.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonClearLogActionPerformed(evt);
            }
        });

        jCheckBoxEnableGCodeLog.setText("Enable GCode Log");
        jCheckBoxEnableGCodeLog.setToolTipText("You may uncheck it on slower computers");
        jCheckBoxEnableGCodeLog.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxEnableGCodeLogActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 619, Short.MAX_VALUE)
                    .addGroup(jPanel4Layout.createSequentialGroup()
                        .addComponent(jButtonClearLog)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(jCheckBoxEnableGCodeLog)))
                .addContainerGap())
        );
        jPanel4Layout.setVerticalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 22, Short.MAX_VALUE)
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonClearLog)
                    .addComponent(jCheckBoxEnableGCodeLog))
                .addContainerGap())
        );

        jTabbedPane1.addTab("GCode Log", jPanel4);

        jLabel9.setText("Each box can contain a series of GCode commands separated by ';'.");

        jLabel10.setText("To execute just click the 'C' button.");

        javax.swing.GroupLayout jPanelMacrosLayout = new javax.swing.GroupLayout(jPanelMacros);
        jPanelMacros.setLayout(jPanelMacrosLayout);
        jPanelMacrosLayout.setHorizontalGroup(
            jPanelMacrosLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMacrosLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMacrosLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel9)
                    .addComponent(jLabel10))
                .addContainerGap(277, Short.MAX_VALUE))
        );
        jPanelMacrosLayout.setVerticalGroup(
            jPanelMacrosLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMacrosLayout.createSequentialGroup()
                .addGap(7, 7, 7)
                .addComponent(jLabel9)
                .addGap(3, 3, 3)
                .addComponent(jLabel10)
                .addContainerGap(441, Short.MAX_VALUE))
        );

        jTabbedPane1.addTab("Macros", jPanelMacros);

        jLabel16.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel16.setText("Last Status Update:");

        jLabelLastStatusUpdate.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelLastStatusUpdate.setText("0");

        jLabel17.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel17.setText("Home Position X,Y,Z:");

        jLabelMachineHomePosition.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachineHomePosition.setText("0,0,0");

        javax.swing.GroupLayout jPanel7Layout = new javax.swing.GroupLayout(jPanel7);
        jPanel7.setLayout(jPanel7Layout);
        jPanel7Layout.setHorizontalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(jLabel16)
                        .addGap(24, 24, 24)
                        .addComponent(jLabelLastStatusUpdate))
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(jLabel17)
                        .addGap(18, 18, 18)
                        .addComponent(jLabelMachineHomePosition, javax.swing.GroupLayout.PREFERRED_SIZE, 199, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(297, Short.MAX_VALUE))
        );
        jPanel7Layout.setVerticalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel16)
                    .addComponent(jLabelLastStatusUpdate))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel17)
                    .addComponent(jLabelMachineHomePosition))
                .addContainerGap(435, Short.MAX_VALUE))
        );

        jTabbedPane1.addTab("Machine Information", jPanel7);

        jPanel6.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createTitledBorder(""), "Vérifications", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 0, 51))); // NOI18N
        jPanel6.setToolTipText("");

        jButtonConnectDisconnect7.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonConnectDisconnect7.setActionCommand("Démarrer la découpe");
        jButtonConnectDisconnect7.setLabel("MAJ les paramètres");
        jButtonConnectDisconnect7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonConnectDisconnect7ActionPerformed(evt);
            }
        });

        jComboBox1.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "V bit", "flat bit", "drill bit", "etc bit" }));
        jComboBox1.setName(""); // NOI18N

        jComboBox2.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Bois", "MDF", "Aluminium", "Plastique", "PCB", "Bronze", "Vinyle" }));
        jComboBox2.setName(""); // NOI18N
        jComboBox2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jComboBox2ActionPerformed(evt);
            }
        });

        jLabel11.setText("Matériel");

        jLabel13.setText("Fraiseuse");

        jLabelRecommendedRPM.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRecommendedRPM.setText("0");

        jLabel43.setText("<html>Définir ces paramètres permets de vérifier la bonne vitesse de rotation et de découpe</html>");

        jLabel46.setText("<html>Vitesse de découpe :<html>");

        jLabel47.setText("Vitesse de rotation :");

        jLabelRecommendedRPM1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRecommendedRPM1.setText("0");

        javax.swing.GroupLayout jPanel6Layout = new javax.swing.GroupLayout(jPanel6);
        jPanel6.setLayout(jPanel6Layout);
        jPanel6Layout.setHorizontalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addGap(4, 4, 4)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel43, javax.swing.GroupLayout.PREFERRED_SIZE, 178, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel11, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jComboBox2, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel13, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jComboBox1, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addGap(130, 130, 130)
                        .addComponent(jLabelRecommendedRPM1, javax.swing.GroupLayout.PREFERRED_SIZE, 30, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jLabel46, javax.swing.GroupLayout.PREFERRED_SIZE, 137, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(jLabel47, javax.swing.GroupLayout.PREFERRED_SIZE, 130, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(jLabelRecommendedRPM, javax.swing.GroupLayout.PREFERRED_SIZE, 30, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jSeparator3, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonConnectDisconnect7, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)))
        );
        jPanel6Layout.setVerticalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addGap(3, 3, 3)
                .addComponent(jLabel43, javax.swing.GroupLayout.PREFERRED_SIZE, 59, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(21, 21, 21)
                .addComponent(jLabel11)
                .addGap(4, 4, 4)
                .addComponent(jComboBox2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jLabel13)
                .addGap(4, 4, 4)
                .addComponent(jComboBox1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabelRecommendedRPM1, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel46, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(5, 5, 5)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel47, javax.swing.GroupLayout.PREFERRED_SIZE, 31, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRecommendedRPM, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(9, 9, 9)
                .addComponent(jSeparator3, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(jButtonConnectDisconnect7)
                .addContainerGap(21, Short.MAX_VALUE))
        );

        jPanel2.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Status", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel2.setToolTipText("");
        jPanel2.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jPanel2MouseMoved(evt);
            }
        });
        jPanel2.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonResetWorkPosition.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonResetWorkPosition.setForeground(new java.awt.Color(255, 255, 255));
        jButtonResetWorkPosition.setText("Ø  Zero Work Position");
        jButtonResetWorkPosition.setToolTipText("Reset the Work Position to 0,0,0");
        jButtonResetWorkPosition.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonResetWorkPositionMouseMoved(evt);
            }
        });
        jButtonResetWorkPosition.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonResetWorkPositionActionPerformed(evt);
            }
        });
        jPanel2.add(jButtonResetWorkPosition, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 240, 32));

        jPanel3.setLayout(new java.awt.GridLayout(1, 0));

        jLabel6.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabel6.setForeground(new java.awt.Color(0, 75, 127));
        jPanel3.add(jLabel6);

        jPanel2.add(jPanel3, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 70, 270, -1));

        jLabelWorkPositionZ.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionZ.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionZ.setText("Z0");
        jLabelWorkPositionZ.setToolTipText("Position de Travail Z ");
        jLabelWorkPositionZ.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionZMouseClicked(evt);
            }
        });
        jPanel2.add(jLabelWorkPositionZ, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 90, 100, 20));

        jLabelWorkPositionX.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionX.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionX.setText("X0");
        jLabelWorkPositionX.setToolTipText("Position de Travail X");
        jLabelWorkPositionX.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionXMouseClicked(evt);
            }
        });
        jPanel2.add(jLabelWorkPositionX, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 30, 100, 20));

        jLabelWorkPositionY.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionY.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionY.setText("Y0");
        jLabelWorkPositionY.setToolTipText("Position de Travail Y");
        jLabelWorkPositionY.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionYMouseClicked(evt);
            }
        });
        jPanel2.add(jLabelWorkPositionY, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 60, 100, 20));

        jLabelMachinePositionZ.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionZ.setText("Z0");
        jLabelMachinePositionZ.setToolTipText("Position Machine Z");
        jPanel2.add(jLabelMachinePositionZ, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 90, 60, 20));

        jLabelMachinePositionX.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionX.setText("X0");
        jLabelMachinePositionX.setToolTipText("Position Machine X");
        jPanel2.add(jLabelMachinePositionX, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 30, 60, 20));

        jLabelMachinePositionY.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionY.setText("Y0");
        jLabelMachinePositionY.setToolTipText("Position Machine Y");
        jPanel2.add(jLabelMachinePositionY, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 60, 60, 20));

        jLabel2.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel2.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel2.setText("Z:");
        jPanel2.add(jLabel2, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 90, 20, 20));

        jLabel3.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel3.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel3.setText("X:");
        jPanel2.add(jLabel3, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 30, 20, 20));

        jLabel12.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel12.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel12.setText("Y:");
        jPanel2.add(jLabel12, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 60, 20, 20));

        jButton1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton1.setText("Ø");
        jButton1.setToolTipText("Click pour définir à  Zero la position de X ");
        jButton1.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButton1MouseMoved(evt);
            }
        });
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 30, 28));

        jButton2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton2.setText("Ø");
        jButton2.setToolTipText("Click pour définir à  Zero la position de X ");
        jButton2.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButton2MouseMoved(evt);
            }
        });
        jButton2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton2ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton2, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 30, 30, 28));

        jButton3.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton3.setText("Ø");
        jButton3.setToolTipText("Click pour définir à  Zero la position de X ");
        jButton3.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButton3MouseMoved(evt);
            }
        });
        jButton3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton3ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton3, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 60, 30, 28));

        jLabelRowsInFile7.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRowsInFile7.setText("Change Outils Semi Auto :");
        jPanel2.add(jLabelRowsInFile7, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 210, 150, 20));

        jLabelSemiAutoToolChangeStatus.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabelSemiAutoToolChangeStatus.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelSemiAutoToolChangeStatus.setText("Off");
        jPanel2.add(jLabelSemiAutoToolChangeStatus, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 210, 80, 20));

        jLabel14.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel14.setText("Vitesse d'Avance:");
        jPanel2.add(jLabel14, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 170, 150, 20));

        jLabelRealTimeFeedRate.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate.setText("0mm/min");
        jPanel2.add(jLabelRealTimeFeedRate, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 170, 80, 20));

        jLabel15.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel15.setText("Vitesse de Rotation:");
        jPanel2.add(jLabel15, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 190, 150, 20));

        jLabelRealTimeSpindleRPM.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM.setText("0");
        jPanel2.add(jLabelRealTimeSpindleRPM, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 190, 80, 20));

        jPanelMachineControl.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Control", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelMachineControl.setToolTipText("");
        jPanelMachineControl.setMinimumSize(new java.awt.Dimension(280, 272));
        jPanelMachineControl.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jPanelMachineControlMouseMoved(evt);
            }
        });

        jRadioButtonInches.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonInches.setText("inch");
        jRadioButtonInches.setToolTipText("Définir l'unitée de mesure en inch");
        jRadioButtonInches.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonInchesActionPerformed(evt);
            }
        });

        jRadioButtonMillimeters.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonMillimeters.setSelected(true);
        jRadioButtonMillimeters.setText("mm");
        jRadioButtonMillimeters.setToolTipText("Définir l'unitée de mesure en mm");
        jRadioButtonMillimeters.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonMillimetersActionPerformed(evt);
            }
        });

        jLabel4.setForeground(new java.awt.Color(255, 255, 255));
        jLabel4.setText("Pas:");

        jSpinnerStep.setModel(new javax.swing.SpinnerNumberModel(1.0d, 0.009999999776482582d, null, 0.009999999776482582d));
        jSpinnerStep.setToolTipText("Pas d'avancement");
        jSpinnerStep.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));

        jButtonYMinus.setForeground(new java.awt.Color(255, 255, 255));
        jButtonYMinus.setText("Y-");
        jButtonYMinus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonYMinusActionPerformed(evt);
            }
        });

        jButtonXMinus.setForeground(new java.awt.Color(255, 255, 255));
        jButtonXMinus.setText("X-");
        jButtonXMinus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonXMinusActionPerformed(evt);
            }
        });

        jButtonYPlus.setForeground(new java.awt.Color(255, 255, 255));
        jButtonYPlus.setText("Y+");
        jButtonYPlus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonYPlusActionPerformed(evt);
            }
        });

        jButtonXPlus.setForeground(new java.awt.Color(255, 255, 255));
        jButtonXPlus.setText("X+");
        jButtonXPlus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonXPlusActionPerformed(evt);
            }
        });

        jButtonZPlus.setForeground(new java.awt.Color(255, 255, 255));
        jButtonZPlus.setText("Z+");
        jButtonZPlus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonZPlusActionPerformed(evt);
            }
        });

        jButtonZMinus.setForeground(new java.awt.Color(255, 255, 255));
        jButtonZMinus.setText("Z-");
        jButtonZMinus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonZMinusActionPerformed(evt);
            }
        });

        jCheckBoxEnableKeyboardJogging.setSelected(true);
        jCheckBoxEnableKeyboardJogging.setText("Activer le jogging par clavier");
        jCheckBoxEnableKeyboardJogging.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxEnableKeyboardJoggingActionPerformed(evt);
            }
        });

        jLabelRemoveFocus.setFont(new java.awt.Font("Tahoma", 0, 9)); // NOI18N
        jLabelRemoveFocus.setForeground(new java.awt.Color(255, 255, 255));
        jLabelRemoveFocus.setText("[Click To Focus]");
        jLabelRemoveFocus.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        jLabelRemoveFocus.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelRemoveFocusMouseClicked(evt);
            }
        });

        jButtonReturnToZero.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonReturnToZero.setForeground(new java.awt.Color(255, 255, 255));
        jButtonReturnToZero.setText("Return to Ø");
        jButtonReturnToZero.setToolTipText("Return to initial Work Position (0,0,0)");
        jButtonReturnToZero.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonReturnToZeroActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanelJogButtonsLayout = new javax.swing.GroupLayout(jPanelJogButtons);
        jPanelJogButtons.setLayout(jPanelJogButtonsLayout);
        jPanelJogButtonsLayout.setHorizontalGroup(
            jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                .addComponent(jButtonXMinus, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addGroup(jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonYPlus, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonYMinus, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButtonXPlus, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jButtonZPlus, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButtonZMinus, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(0, 0, Short.MAX_VALUE))
            .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jCheckBoxEnableKeyboardJogging)
                        .addGap(18, 18, 18)
                        .addComponent(jLabelRemoveFocus))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanelJogButtonsLayout.createSequentialGroup()
                        .addComponent(jButtonReturnToZero, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap())))
        );
        jPanelJogButtonsLayout.setVerticalGroup(
            jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanelJogButtonsLayout.createSequentialGroup()
                            .addGap(21, 21, 21)
                            .addComponent(jButtonXPlus, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGap(23, 23, 23))
                        .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                            .addComponent(jButtonYPlus, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(jButtonYMinus, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                        .addGap(21, 21, 21)
                        .addComponent(jButtonXMinus, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanelJogButtonsLayout.createSequentialGroup()
                        .addComponent(jButtonZPlus, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButtonZMinus, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 11, Short.MAX_VALUE)
                .addGroup(jPanelJogButtonsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jCheckBoxEnableKeyboardJogging)
                    .addComponent(jLabelRemoveFocus))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jButtonReturnToZero, javax.swing.GroupLayout.PREFERRED_SIZE, 32, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        jSliderStepSize.setMaximum(5);
        jSliderStepSize.setMinorTickSpacing(1);
        jSliderStepSize.setPaintLabels(true);
        jSliderStepSize.setPaintTicks(true);
        jSliderStepSize.setSnapToTicks(true);
        jSliderStepSize.setToolTipText("");
        jSliderStepSize.setValue(3);
        jSliderStepSize.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jSliderStepSizeStateChanged(evt);
            }
        });

        javax.swing.GroupLayout jPanel17Layout = new javax.swing.GroupLayout(jPanel17);
        jPanel17.setLayout(jPanel17Layout);
        jPanel17Layout.setHorizontalGroup(
            jPanel17Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 256, Short.MAX_VALUE)
        );
        jPanel17Layout.setVerticalGroup(
            jPanel17Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 16, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout jPanelMachineControlLayout = new javax.swing.GroupLayout(jPanelMachineControl);
        jPanelMachineControl.setLayout(jPanelMachineControlLayout);
        jPanelMachineControlLayout.setHorizontalGroup(
            jPanelMachineControlLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMachineControlLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMachineControlLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSliderStepSize, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanelJogButtons, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(jPanelMachineControlLayout.createSequentialGroup()
                        .addGroup(jPanelMachineControlLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jPanel17, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(jPanelMachineControlLayout.createSequentialGroup()
                                .addComponent(jLabel4)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jSpinnerStep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jRadioButtonInches)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(jRadioButtonMillimeters)))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanelMachineControlLayout.setVerticalGroup(
            jPanelMachineControlLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMachineControlLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelMachineControlLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel4)
                    .addComponent(jSpinnerStep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jRadioButtonInches)
                    .addComponent(jRadioButtonMillimeters))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jSliderStepSize, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jPanelJogButtons, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel17, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanelConnection.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Connection", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelConnection.setToolTipText("");
        jPanelConnection.setVerifyInputWhenFocusTarget(false);
        jPanelConnection.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jPanelConnectionMouseMoved(evt);
            }
        });
        jPanelConnection.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonSoftReset.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonSoftReset.setText("Soft Reset");
        jButtonSoftReset.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonSoftResetMouseMoved(evt);
            }
        });
        jButtonSoftReset.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonSoftResetActionPerformed(evt);
            }
        });
        jPanelConnection.add(jButtonSoftReset, new org.netbeans.lib.awtextra.AbsoluteConstraints(120, 50, -1, -1));

        jLabelMachineX1.setText("Status:");
        jPanelConnection.add(jLabelMachineX1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 20, 40, 20));

        jButtonKillAlarm.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonKillAlarm.setText("Kill Alarm");
        jButtonKillAlarm.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonKillAlarmMouseMoved(evt);
            }
        });
        jButtonKillAlarm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonKillAlarmActionPerformed(evt);
            }
        });
        jPanelConnection.add(jButtonKillAlarm, new org.netbeans.lib.awtextra.AbsoluteConstraints(190, 20, -1, -1));

        jLabelActiveState.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jLabelActiveState.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelActiveState.setText("Restarting...");
        jLabelActiveState.addInputMethodListener(new java.awt.event.InputMethodListener() {
            public void caretPositionChanged(java.awt.event.InputMethodEvent evt) {
            }
            public void inputMethodTextChanged(java.awt.event.InputMethodEvent evt) {
                jLabelActiveStateInputMethodTextChanged(evt);
            }
        });
        jLabelActiveState.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                jLabelActiveStatePropertyChange(evt);
            }
        });
        jPanelConnection.add(jLabelActiveState, new org.netbeans.lib.awtextra.AbsoluteConstraints(70, 20, 120, 20));

        jButtonConnectDisconnect1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonConnectDisconnect1.setText("Disconnect");
        jButtonConnectDisconnect1.setName("jButtonConnectDisconnect"); // NOI18N
        jButtonConnectDisconnect1.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jButtonConnectDisconnect1MouseMoved(evt);
            }
        });
        jButtonConnectDisconnect1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonConnectDisconnect1ActionPerformed(evt);
            }
        });
        jPanelConnection.add(jButtonConnectDisconnect1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 50, -1, -1));
        jButtonConnectDisconnect1.getAccessibleContext().setAccessibleDescription("");

        jLayeredPane1.setLayer(jPanel2, javax.swing.JLayeredPane.DEFAULT_LAYER);
        jLayeredPane1.setLayer(jPanelMachineControl, javax.swing.JLayeredPane.DEFAULT_LAYER);
        jLayeredPane1.setLayer(jPanelConnection, javax.swing.JLayeredPane.DEFAULT_LAYER);

        javax.swing.GroupLayout jLayeredPane1Layout = new javax.swing.GroupLayout(jLayeredPane1);
        jLayeredPane1.setLayout(jLayeredPane1Layout);
        jLayeredPane1Layout.setHorizontalGroup(
            jLayeredPane1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jLayeredPane1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jLayeredPane1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanelConnection, javax.swing.GroupLayout.PREFERRED_SIZE, 282, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jPanelMachineControl, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jLayeredPane1Layout.setVerticalGroup(
            jLayeredPane1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jLayeredPane1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanelConnection, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanelMachineControl, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        jPanel16.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Information Navigation", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 0, 14), new java.awt.Color(255, 255, 255))); // NOI18N

        jLabel49.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        jLabel49.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);

        javax.swing.GroupLayout jPanel16Layout = new javax.swing.GroupLayout(jPanel16);
        jPanel16.setLayout(jPanel16Layout);
        jPanel16Layout.setHorizontalGroup(
            jPanel16Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel16Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel49, javax.swing.GroupLayout.PREFERRED_SIZE, 166, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel16Layout.setVerticalGroup(
            jPanel16Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel16Layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel49, javax.swing.GroupLayout.PREFERRED_SIZE, 203, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        jMenu1.setText("System");

        jMenuItemGRBLSettings.setText("GRBL Settings");
        jMenuItemGRBLSettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemGRBLSettingsActionPerformed(evt);
            }
        });
        jMenu1.add(jMenuItemGRBLSettings);

        jMenuItemExit.setText("Exit");
        jMenuItemExit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemExitActionPerformed(evt);
            }
        });
        jMenu1.add(jMenuItemExit);

        jMenuBar1.add(jMenu1);

        jMenu2.setText("Tools");
        jMenu2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenu2ActionPerformed(evt);
            }
        });

        jMenuSetWorkPos.setIcon(new javax.swing.ImageIcon(getClass().getResource("/sourcerabbit/gcode/sender/UI/Images/WorkArea-24x24.png"))); // NOI18N
        jMenuSetWorkPos.setText("Set Work Position");
        jMenuSetWorkPos.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuSetWorkPosActionPerformed(evt);
            }
        });
        jMenu2.add(jMenuSetWorkPos);

        jMenuItem2.setIcon(new javax.swing.ImageIcon(getClass().getResource("/sourcerabbit/gcode/sender/UI/Images/ZTouchProbe/ZAxisTouchProbe-24x24.png"))); // NOI18N
        jMenuItem2.setText("Z Axis Touch Probe");
        jMenuItem2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem2ActionPerformed(evt);
            }
        });
        jMenu2.add(jMenuItem2);

        jMenuItemHoleCenterFinder.setIcon(new javax.swing.ImageIcon(getClass().getResource("/sourcerabbit/gcode/sender/UI/Images/HoleCenterFinder/HoleCenterFinder-24x24.png"))); // NOI18N
        jMenuItemHoleCenterFinder.setText("Hole Center Finder");
        jMenuItemHoleCenterFinder.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemHoleCenterFinderActionPerformed(evt);
            }
        });
        jMenu2.add(jMenuItemHoleCenterFinder);

        jMenuBar1.add(jMenu2);

        jMenu4.setText("Machine");

        jMenuItemStartHomingSequence.setText("Start Homing Sequence");
        jMenuItemStartHomingSequence.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemStartHomingSequenceActionPerformed(evt);
            }
        });
        jMenu4.add(jMenuItemStartHomingSequence);

        jMenuItemToolChangeSettings.setText("Tool Change Settings");
        jMenuItemToolChangeSettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemToolChangeSettingsActionPerformed(evt);
            }
        });
        jMenu4.add(jMenuItemToolChangeSettings);

        jMenuBar1.add(jMenu4);

        jMenu5.setText("Tutoriel");

        jMenuItem5.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        jMenuItem5.setText("Tutoriel Logiciel");
        jMenuItem5.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                jMenuItem5MouseMoved(evt);
            }
        });
        jMenuItem5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem5ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem5);

        jMenuItem9.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        jMenuItem9.setText("Tutoriel découpe complet");
        jMenuItem9.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem9ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem9);
        jMenu5.add(jSeparator19);

        jMenuItem10.setText("GCode : Guide de conversion");
        jMenuItem10.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem10ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem10);

        jMenuItem1.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        jMenuItem1.setText("GCode : Apprendre les bases");
        jMenuItem1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem1ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem1);
        jMenu5.add(jSeparator22);

        jMenuItem11.setText("CNC : Les Sécuritées ");
        jMenu5.add(jMenuItem11);

        jMenuItem12.setText("CNC :  Les Limitations");
        jMenu5.add(jMenuItem12);

        jMenuBar1.add(jMenu5);

        jMenu3.setText("Help");

        jMenuItem3.setText("About");
        jMenuItem3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem3ActionPerformed(evt);
            }
        });
        jMenu3.add(jMenuItem3);

        jMenuItem4.setText("Check for Update");
        jMenuItem4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem4ActionPerformed(evt);
            }
        });
        jMenu3.add(jMenuItem4);

        jMenuBar1.add(jMenu3);

        jMenu6.setText("Level");
        jMenu6.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
        jMenu6.setName("jMenuLevel"); // NOI18N
        jMenu6.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jMenu6StateChanged(evt);
            }
        });
        jMenu6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenu6ActionPerformed(evt);
            }
        });

        jMenuItem6.setText("Débutant");
        jMenuItem6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem6ActionPerformed(evt);
            }
        });
        jMenu6.add(jMenuItem6);

        jMenuItem7.setText("Intermédiaire");
        jMenuItem7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem7ActionPerformed(evt);
            }
        });
        jMenu6.add(jMenuItem7);

        jMenuItem8.setText("Expert");
        jMenuItem8.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem8ActionPerformed(evt);
            }
        });
        jMenu6.add(jMenuItem8);

        jMenuBar1.add(jMenu6);

        setJMenuBar(jMenuBar1);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(jLayeredPane1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(1, 1, 1)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(jTabbedPane1)
                    .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel16, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jPanel6, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(8, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jLayeredPane1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jPanel16, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(jPanel6, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, 154, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jTabbedPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE))))
        );

        getAccessibleContext().setAccessibleName("frmControl");

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void jButtonYPlusActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonYPlusActionPerformed
    {//GEN-HEADEREND:event_jButtonYPlusActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Y", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonYPlusActionPerformed

    private void jButtonYMinusActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonYMinusActionPerformed
    {//GEN-HEADEREND:event_jButtonYMinusActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Y-", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonYMinusActionPerformed

    private void jButtonXPlusActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonXPlusActionPerformed
    {//GEN-HEADEREND:event_jButtonXPlusActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "X", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonXPlusActionPerformed

    private void jButtonXMinusActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonXMinusActionPerformed
    {//GEN-HEADEREND:event_jButtonXMinusActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "X-", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonXMinusActionPerformed

    private void jRadioButtonInchesActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jRadioButtonInchesActionPerformed
    {//GEN-HEADEREND:event_jRadioButtonInchesActionPerformed
        // Inches Selected!
        jRadioButtonMillimeters.setSelected(false);
        fJoggingUnits = EUnits.Imperial;
    }//GEN-LAST:event_jRadioButtonInchesActionPerformed

    private void jRadioButtonMillimetersActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jRadioButtonMillimetersActionPerformed
    {//GEN-HEADEREND:event_jRadioButtonMillimetersActionPerformed
        // Millimeters Selected!
        jRadioButtonInches.setSelected(false);
        fJoggingUnits = EUnits.Metric;
    }//GEN-LAST:event_jRadioButtonMillimetersActionPerformed

    private void jButtonZMinusActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonZMinusActionPerformed
    {//GEN-HEADEREND:event_jButtonZMinusActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Z-", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonZMinusActionPerformed

    private void jButtonZPlusActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonZPlusActionPerformed
    {//GEN-HEADEREND:event_jButtonZPlusActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Z", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonZPlusActionPerformed

    private void jButtonGCodeBrowseActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonGCodeBrowseActionPerformed
    {//GEN-HEADEREND:event_jButtonGCodeBrowseActionPerformed
        final String path = SettingsManager.getLastGCodeBrowsedDirectory();
        JFileChooser fc;
        try
        {
            fc = new JFileChooser(new File(path));
            FileNameExtensionFilter filter = new FileNameExtensionFilter("GCode Files (.nc, .gcode, .tap, .gc)", "nc", "gcode", "tap", "gc");
            fc.setFileFilter(filter);
        }
        catch (Exception ex)
        {
            fc = new JFileChooser();
        }
        int returnVal = fc.showOpenDialog(this);

        if (fc.getSelectedFile() != null && returnVal == JFileChooser.APPROVE_OPTION)
        {
            File gcodeFile = fc.getSelectedFile();
            String gcodeFilePath = fc.getSelectedFile().getPath();
            jTextFieldGCodeFile.setText(gcodeFilePath);

            SettingsManager.setLastGCodeBrowsedDirectory(gcodeFile.getParent());

            // Ask the GCodeSender of the active connection handler to load the GCode File
            if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().LoadGCodeFile(gcodeFile))
            {
                jLabelRowsInFile.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsInFile()));
            }
        }
    }//GEN-LAST:event_jButtonGCodeBrowseActionPerformed

    private void jButtonGCodeSendActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonGCodeSendActionPerformed
    {//GEN-HEADEREND:event_jButtonGCodeSendActionPerformed
        boolean startCycle = true;
        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getX() != 0
                || ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getY() != 0
                || ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getZ() != 0)
        {
            startCycle = false;
            int answer = JOptionPane.showConfirmDialog(
                    null,
                    "La position de travail n'est pas 0,0,0.\nVoulez vous démarrer le cycle G Code?",
                    "Position de travail n'est pas 0,0,0",
                    JOptionPane.YES_NO_OPTION);

            startCycle = (answer == JOptionPane.YES_OPTION);
        }

        if (startCycle)
        {
            EnableOrDisableComponentsWhenMachineIsCyclingGCode(true);
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().StartSendingGCode();
        }
    }//GEN-LAST:event_jButtonGCodeSendActionPerformed

    private void jButtonGCodePauseActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonGCodePauseActionPerformed
    {//GEN-HEADEREND:event_jButtonGCodePauseActionPerformed
        if (jButtonGCodePause.getText().equals("Pause"))
        {
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().PauseSendingGCode();
            jButtonGCodePause.setText("Resume");
        }
        else
        {
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().ResumeSendingGCode();
            jButtonGCodePause.setText("Pause");
        }
    }//GEN-LAST:event_jButtonGCodePauseActionPerformed

    private void jButtonGCodeCancelActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonGCodeCancelActionPerformed
    {//GEN-HEADEREND:event_jButtonGCodeCancelActionPerformed

        // Create a new thread to send the Cancel command
        // in order NOT to pause the UI !
        Thread th = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                jButtonGCodeCancel.setEnabled(false);
                jLabelActiveState.setText("Canceling GCode Cycle...");
                jLabelActiveState.setForeground(Color.red);
                ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().CancelSendingGCode(false);
            }
        });
        th.start();

    }//GEN-LAST:event_jButtonGCodeCancelActionPerformed

    private void jButtonReturnToZeroActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonReturnToZeroActionPerformed
    {//GEN-HEADEREND:event_jButtonReturnToZeroActionPerformed
        try
        {
            final Position4D machinePos = ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMachinePosition();
            if (machinePos.getX() != 0 || machinePos.getY() != 0 || machinePos.getZ() != 0)
            {
                String response = "";
                if (machinePos.getZ() <= 2)
                {
                    final GCodeCommand command1 = new GCodeCommand("G21 G90 G0 Z2");
                    response = ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommandAndGetResponse(command1);
                }

                if (response.equals("ok"))
                {
                    final GCodeCommand command2 = new GCodeCommand("G21 G90 X0 Y0");
                    response = ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommandAndGetResponse(command2);
                }

                if (response.equals("ok"))
                {
                    final GCodeCommand command3 = new GCodeCommand("G21 G90 G0 Z0");
                    ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommand(command3);
                }

                if (response.equals("ok"))
                {
                    WriteToConsole("Return to zero");
                }
                else
                {
                    WriteToConsole("Failed to return to zero");
                }

            }
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jButtonReturnToZeroActionPerformed

    private void jButtonSoftResetActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonSoftResetActionPerformed
    {//GEN-HEADEREND:event_jButtonSoftResetActionPerformed
        try
        {
            WriteToConsole("Restarting...");
            jLabelActiveState.setForeground(Color.MAGENTA);
            jLabelActiveState.setText("Restarting...");
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendDataImmediately_WithoutMessageCollector(GRBLCommands.COMMAND_SOFT_RESET);
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().KillGCodeCycle();
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jButtonSoftResetActionPerformed

    private void jButtonKillAlarmActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonKillAlarmActionPerformed
    {//GEN-HEADEREND:event_jButtonKillAlarmActionPerformed
        try
        {
            // Send Kill Alarm lock command for both Kill Alarm and Machine Unlock
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendData(GRBLCommands.COMMAND_KILL_ALARM_LOCK);
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jButtonKillAlarmActionPerformed

    private void jButtonGCodeVisualizeActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonGCodeVisualizeActionPerformed
    {//GEN-HEADEREND:event_jButtonGCodeVisualizeActionPerformed
        try
        {
            final Chart2D chart = new Chart2D();
            // Create an ITrace: 
            ITrace2D trace = new Trace2DSimple();
            // Add the trace to the chart. This has to be done before adding points (deadlock prevention): 
            chart.addTrace(trace);

            final Queue<String> gcodeQueue = new ArrayDeque(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeQueue());
            double x = 0, y = 0, z = 0, maxX = 0, maxY = 0;

            while (gcodeQueue.size() > 0)
            {
                final GCodeCommand command = new GCodeCommand(gcodeQueue.remove());
                x = (command.getCoordinates().getX() != null) ? command.getCoordinates().getX() : x;
                y = (command.getCoordinates().getY() != null) ? command.getCoordinates().getY() : y;
                z = (command.getCoordinates().getZ() != null) ? command.getCoordinates().getZ() : z;

                if (z < 0)
                {
                    maxX = Math.max(x, maxX);
                    maxY = Math.max(y, maxY);
                    trace.addPoint(x, y);
                }
            }

            chart.getAxisX().setRangePolicy(new RangePolicyFixedViewport(new Range(0, Math.max(maxY, maxX))));
            chart.getAxisY().setRangePolicy(new RangePolicyFixedViewport(new Range(0, Math.max(maxY, maxX))));

            // Make it visible:
            // Create a frame.
            final JFrame frame = new JFrame(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeFile().getName());
            // add the chart to the frame: 
            frame.getContentPane().add(chart);
            frame.setSize(600, 600);
            frame.setVisible(true);
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jButtonGCodeVisualizeActionPerformed

    private void jButtonClearLogActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonClearLogActionPerformed
    {//GEN-HEADEREND:event_jButtonClearLogActionPerformed
        try
        {
            synchronized (fAddRemoveLogTableLines)
            {
                DefaultTableModel model = (DefaultTableModel) jTableGCodeLog.getModel();
                int rowCount = model.getRowCount();
                for (int i = rowCount - 1; i >= 0; i--)
                {
                    model.removeRow(i);
                }
            }
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jButtonClearLogActionPerformed

    private void jCheckBoxEnableGCodeLogActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jCheckBoxEnableGCodeLogActionPerformed
    {//GEN-HEADEREND:event_jCheckBoxEnableGCodeLogActionPerformed
        try
        {
            SettingsManager.setIsGCodeLogEnabled(jCheckBoxEnableGCodeLog.isSelected());
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jCheckBoxEnableGCodeLogActionPerformed

    private void jTextFieldCommandActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jTextFieldCommandActionPerformed
    {//GEN-HEADEREND:event_jTextFieldCommandActionPerformed
        try
        {
            final String str = this.jTextFieldCommand.getText().replaceAll("(\\r\\n|\\n\\r|\\r|\\n)", "");
            GCodeCommand command = new GCodeCommand(str);
            String response = ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommandAndGetResponse(command);

            WriteToConsole(str + "\nResponse:" + response + "\n");

            jTextFieldCommand.setText("");
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jTextFieldCommandActionPerformed

    private void jButtonClearConsoleActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonClearConsoleActionPerformed
    {//GEN-HEADEREND:event_jButtonClearConsoleActionPerformed
        jTextAreaConsole.setText("");
    }//GEN-LAST:event_jButtonClearConsoleActionPerformed

    private void jTextFieldGCodeFileActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jTextFieldGCodeFileActionPerformed
    {//GEN-HEADEREND:event_jTextFieldGCodeFileActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jTextFieldGCodeFileActionPerformed

    private void jMenuItemGRBLSettingsActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItemGRBLSettingsActionPerformed
    {//GEN-HEADEREND:event_jMenuItemGRBLSettingsActionPerformed
        frmGRBLSettings frm = new frmGRBLSettings();
        frm.setModal(true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItemGRBLSettingsActionPerformed

    private void jMenuItemExitActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItemExitActionPerformed
    {//GEN-HEADEREND:event_jMenuItemExitActionPerformed
        System.exit(EXIT_ON_CLOSE);
    }//GEN-LAST:event_jMenuItemExitActionPerformed

    private void jMenuSetWorkPosActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuSetWorkPosActionPerformed
    {//GEN-HEADEREND:event_jMenuSetWorkPosActionPerformed
        frmSetWorkPosition frm = new frmSetWorkPosition(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuSetWorkPosActionPerformed

    private void jMenuItem2ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItem2ActionPerformed
    {//GEN-HEADEREND:event_jMenuItem2ActionPerformed
        frmZAxisTouchProbe frm = new frmZAxisTouchProbe(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItem2ActionPerformed

    private void jMenuItem3ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItem3ActionPerformed
    {//GEN-HEADEREND:event_jMenuItem3ActionPerformed
        frmAbout frm = new frmAbout(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItem3ActionPerformed

    private void jMenuItem4ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItem4ActionPerformed
    {//GEN-HEADEREND:event_jMenuItem4ActionPerformed
        frmCheckForUpdate frm = new frmCheckForUpdate(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItem4ActionPerformed

    private void jMenuItemHoleCenterFinderActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItemHoleCenterFinderActionPerformed
    {//GEN-HEADEREND:event_jMenuItemHoleCenterFinderActionPerformed
        frmHoleCenterFinder frm = new frmHoleCenterFinder(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItemHoleCenterFinderActionPerformed

    private void jLabelRemoveFocusMouseClicked(java.awt.event.MouseEvent evt)//GEN-FIRST:event_jLabelRemoveFocusMouseClicked
    {//GEN-HEADEREND:event_jLabelRemoveFocusMouseClicked
        requestFocus();
    }//GEN-LAST:event_jLabelRemoveFocusMouseClicked

    private void jCheckBoxEnableKeyboardJoggingActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jCheckBoxEnableKeyboardJoggingActionPerformed
    {//GEN-HEADEREND:event_jCheckBoxEnableKeyboardJoggingActionPerformed
        try
        {
            SettingsManager.setIsKeyboardJoggingEnabled(jCheckBoxEnableKeyboardJogging.isSelected());
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jCheckBoxEnableKeyboardJoggingActionPerformed

    private void jMenuItemToolChangeSettingsActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItemToolChangeSettingsActionPerformed
    {//GEN-HEADEREND:event_jMenuItemToolChangeSettingsActionPerformed
        frmToolChangeSettings frm = new frmToolChangeSettings(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItemToolChangeSettingsActionPerformed

    private void jCheckBoxShowVerboseOutputActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jCheckBoxShowVerboseOutputActionPerformed
    {//GEN-HEADEREND:event_jCheckBoxShowVerboseOutputActionPerformed
        ConnectionHelper.ACTIVE_CONNECTION_HANDLER.setShowVerboseOutput(jCheckBoxShowVerboseOutput.isSelected());
    }//GEN-LAST:event_jCheckBoxShowVerboseOutputActionPerformed

    private void jButtonResetWorkPositionActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButtonResetWorkPositionActionPerformed
    {//GEN-HEADEREND:event_jButtonResetWorkPositionActionPerformed
        try
        {
            int input = JOptionPane.showConfirmDialog(null, "Do you want to zero X,Y and Z axis ?", "Zero All Positions", JOptionPane.YES_NO_OPTION);
            if (input == JOptionPane.YES_OPTION)
            {
                Process_ZeroWorkPosition process = new Process_ZeroWorkPosition(new String[]
                {
                    "X", "Y", "Z"
                });
                process.Execute();
            }
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jButtonResetWorkPositionActionPerformed

    private void jLabelWorkPositionXMouseClicked(java.awt.event.MouseEvent evt)//GEN-FIRST:event_jLabelWorkPositionXMouseClicked
    {//GEN-HEADEREND:event_jLabelWorkPositionXMouseClicked

    }//GEN-LAST:event_jLabelWorkPositionXMouseClicked

    private void jLabelWorkPositionYMouseClicked(java.awt.event.MouseEvent evt)//GEN-FIRST:event_jLabelWorkPositionYMouseClicked
    {//GEN-HEADEREND:event_jLabelWorkPositionYMouseClicked

    }//GEN-LAST:event_jLabelWorkPositionYMouseClicked

    private void jLabelWorkPositionZMouseClicked(java.awt.event.MouseEvent evt)//GEN-FIRST:event_jLabelWorkPositionZMouseClicked
    {//GEN-HEADEREND:event_jLabelWorkPositionZMouseClicked
        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getActiveState() == GRBLActiveStates.IDLE && !ConnectionHelper.AUTO_TOOL_CHANGE_OPERATION_IS_ACTIVE)
        {
            if (evt.getClickCount() == 2 && !evt.isConsumed())
            {
                evt.consume();

                int input = JOptionPane.showConfirmDialog(null, "Do you want to zero Z axis?", "Zero Z Axis", JOptionPane.YES_NO_OPTION);
                if (input == JOptionPane.YES_OPTION)
                {
                    String commandStr = "G92 Z0";
                    GCodeCommand command = new GCodeCommand(commandStr);
                    ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommandAndGetResponse(command);
                }
            }
        }
    }//GEN-LAST:event_jLabelWorkPositionZMouseClicked

    private void jSliderStepSizeStateChanged(javax.swing.event.ChangeEvent evt)//GEN-FIRST:event_jSliderStepSizeStateChanged
    {//GEN-HEADEREND:event_jSliderStepSizeStateChanged
        int value = jSliderStepSize.getValue();
        switch (value)
        {
            case 0:
                jSpinnerStep.setValue(0.001);
                break;

            case 1:
                jSpinnerStep.setValue(0.01);
                break;

            case 2:
                jSpinnerStep.setValue(0.1);
                break;

            case 3:
                jSpinnerStep.setValue(1.0);
                break;

            case 4:
                jSpinnerStep.setValue(10.0);
                break;

            case 5:
                jSpinnerStep.setValue(100.0);
                break;

        }
    }//GEN-LAST:event_jSliderStepSizeStateChanged

    private void jMenuItemStartHomingSequenceActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItemStartHomingSequenceActionPerformed
    {//GEN-HEADEREND:event_jMenuItemStartHomingSequenceActionPerformed
        try
        {
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommand(new GCodeCommand("$H"));
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jMenuItemStartHomingSequenceActionPerformed

    private void jButton2ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButton2ActionPerformed
    {//GEN-HEADEREND:event_jButton2ActionPerformed
        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getActiveState() == GRBLActiveStates.IDLE && !ConnectionHelper.AUTO_TOOL_CHANGE_OPERATION_IS_ACTIVE)
        {
            int input = JOptionPane.showConfirmDialog(null, "Do you want to zero X axis?", "Zero X Axis", JOptionPane.YES_NO_OPTION);
            if (input == JOptionPane.YES_OPTION)
            {
                Process_ZeroWorkPosition process = new Process_ZeroWorkPosition(new String[]
                {
                    "X"
                });
                process.Execute();
            }
        }
    }//GEN-LAST:event_jButton2ActionPerformed

    private void jButton3ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButton3ActionPerformed
    {//GEN-HEADEREND:event_jButton3ActionPerformed
        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getActiveState() == GRBLActiveStates.IDLE && !ConnectionHelper.AUTO_TOOL_CHANGE_OPERATION_IS_ACTIVE)
        {

            int input = JOptionPane.showConfirmDialog(null, "Do you want to zero Y axis?", "Zero Y Axis", JOptionPane.YES_NO_OPTION);
            if (input == JOptionPane.YES_OPTION)
            {
                Process_ZeroWorkPosition process = new Process_ZeroWorkPosition(new String[]
                {
                    "Y"
                });
                process.Execute();
            }
        }

    }//GEN-LAST:event_jButton3ActionPerformed

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jButton1ActionPerformed
    {//GEN-HEADEREND:event_jButton1ActionPerformed
        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getActiveState() == GRBLActiveStates.IDLE && !ConnectionHelper.AUTO_TOOL_CHANGE_OPERATION_IS_ACTIVE)
        {
            int input = JOptionPane.showConfirmDialog(null, "Do you want to zero Z axis?", "Zero Z Axis", JOptionPane.YES_NO_OPTION);
            if (input == JOptionPane.YES_OPTION)
            {
                Process_ZeroWorkPosition process = new Process_ZeroWorkPosition(new String[]
                {
                    "Z"
                });
                process.Execute();
            }
        }
    }//GEN-LAST:event_jButton1ActionPerformed

    private void UpdateLevel(){
        switch(level){
            case 1:
                jMenu6.setText("Débutant");
                break;
            case 2:
                jMenu6.setText("Intermédiaire");
                break;
            case 3:
                jMenu6.setText("Expert");
                break;
        }         
    }
    private void jMenuItem5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem5ActionPerformed
        if(tutorial){    
        tutorial=false;
         /***********DEBUT DU TUTORIEL *****************/
         int verified_level =0;
        if(jMenu6.getText()=="Level"){
        // Choix du level
        jDialogLevel.pack();
        jDialogLevel.setLocationRelativeTo(null); 
        jDialogLevel.setVisible(true);
        verified_level = jLevelCombo.getSelectedIndex() + 1;
        UpdateLevel();
        jButton7.requestFocus();
        jButtonOk.requestFocus();  
        jButtonOk1.requestFocus();  
        jButtonOk2.requestFocus();  
        jButtonOk3.requestFocus();  
        jButtonOk4.requestFocus();  
   
        }
        else{
            if(null!=jMenu6.getText())switch (jMenu6.getText()) {
                case "Expert":
                    verified_level = 3;
                    break;
                case "Intermédiaire":
                    verified_level = 2;
                    break;
                case "Débutant":
                    verified_level = 1;
                    break;
                default:
                    break;
            }
        }
           
                    // Vider tous les Panels sauf CONNECTION
                    /* Elements de Machine Status */
                    jButtonVisualise.setVisible(false);
                    jButtonBrowse.setVisible(false);
                    jLabelMachinePositionX.setVisible(false);
                    jLabelRealTimeFeedRate.setVisible(false);
                    jLabel14.setVisible(false);
                    jLabelWorkPositionZ.setVisible(false);
                    jLabelSemiAutoToolChangeStatus.setVisible(false);
                    jLabelMachinePositionY.setVisible(false);
                    jLabelRowsInFile7.setVisible(false);
                    jLabelWorkPositionX.setVisible(false);
                    jButtonResetWorkPosition.setVisible(false);
                    jLabelMachinePositionZ.setVisible(false);
                    jLabel12.setVisible(false);
                    jLabelRealTimeSpindleRPM.setVisible(false);
                    jLabelWorkPositionY.setVisible(false);
                    jButton3.setVisible(false);
                    jLabel15.setVisible(false);
                    jButton2.setVisible(false);
                    jLabel2.setVisible(false);
                    jLabel3.setVisible(false);
                    jButton1.setVisible(false);
                    /* Elements de Machine Control */
                    jRadioButtonMillimeters.setVisible(false);
                    jButtonZMinus.setVisible(false);
                    jCheckBoxEnableKeyboardJogging.setVisible(false);
                    jButtonZPlus.setVisible(false);
                    jButtonXPlus.setVisible(false);
                    jLabel4.setVisible(false);
                    jRadioButtonInches.setVisible(false);
                    jSliderStepSize.setVisible(false);
                    jLabelRemoveFocus.setVisible(false);
                    jButtonXMinus.setVisible(false);
                    jButtonReturnToZero.setVisible(false);
                    jSpinnerStep.setVisible(false);
                    jButtonYPlus.setVisible(false);
                    jButtonYMinus.setVisible(false);
                    /* Elements de  GCode SENDER */
                    jButtonGCodePause.setVisible(false);
                    jLabelRowsInFile3.setVisible(false);
                    jLabelRowsInFile2.setVisible(false);
                    jLabelRowsInFile.setVisible(false);
                    jLabelSentRows.setVisible(false);
                    jButtonGCodeSend.setVisible(false);
                    jProgressBarGCodeProgress.setVisible(false);
                    jButtonGCodeCancel.setVisible(false);
                    jLabelRowsInFile1.setVisible(false);
                    jLabelRowsInFile4.setVisible(false);
                    jLabelTimeElapsed.setVisible(false);
                    jLabelRowsInFile5.setVisible(false);
                    jTextFieldGCodeFile.setVisible(false);
                    jLabel5.setVisible(false);
                    jLabelRemainingRows.setVisible(false);
                    /* Elements de TabbedPane */
                    /* TAB 1*/
                    jLabel7.setVisible(false);
                    jTextAreaConsole.setVisible(false);
                    jCheckBoxShowVerboseOutput.setVisible(false);
                    jTextFieldCommand.setVisible(false);
                    jButtonClearConsole.setVisible(false);
                    /* TAB 2*/
                    jTableGCodeLog.setVisible(false);
                    jButtonClearLog.setVisible(false);
                    jCheckBoxEnableGCodeLog.setVisible(false);
                    /* TAB 3*/
                    jLabel9.setVisible(false);
                    jLabel10.setVisible(false);
                    /* TAB 4*/
                    jLabel16.setVisible(false);
                    jLabelMachineHomePosition.setVisible(false);
                    jLabelLastStatusUpdate.setVisible(false);
                    jLabel17.setVisible(false);
                    jButtonGCodeVisualize.setVisible(false);
                    jButtonGCodeBrowse.setVisible(false);
                    jDialog1.pack();
                    jDialog1.setLocationRelativeTo(null);
                    jDialog1.setVisible(true);
                    if(tutorial){
                        
                    /***** FIN DU TUTO *******/
                    /*Element Connection*/
                    jButtonGCodeVisualize.setVisible(true);jButtonGCodeBrowse.setVisible(true);jButtonVisualise.setVisible(true);jButtonBrowse.setVisible(true);
                    jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                    /* Elements Statut*/
                    jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                    /* Elements de Machine Control */
                    jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                    /* Elements de  GCode SENDER */
                    jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                    /* Elements de TabbedPane */
                    /* TAB 1*/
                    jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                    /* TAB 2*/
                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                     /* TAB 3*/
                    jLabel9.setVisible(true); jLabel10.setVisible(true);
                     /* TAB 4*/
                    jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                                                   
                    }else{
                    /* Elements de Connection Invisible*/
                    jButtonSoftReset.setVisible(false);
                    jLabelMachineX1.setVisible(false);
                    jButtonKillAlarm.setVisible(false);
                    jButtonConnectDisconnect1.setVisible(false);
                    jLabelActiveState.setVisible(false);
                    /* Elements de Machine Statuts Visible */
                    jLabelMachinePositionX.setVisible(true);
                    jLabelRealTimeFeedRate.setVisible(true);
                    jLabel14.setVisible(true);
                    jLabelWorkPositionZ.setVisible(true);
                    jLabelSemiAutoToolChangeStatus.setVisible(true);
                    jLabelMachinePositionY.setVisible(true);
                    jLabelRowsInFile7.setVisible(true);
                    jLabelWorkPositionX.setVisible(true);
                    jButtonResetWorkPosition.setVisible(true);
                    jLabelMachinePositionZ.setVisible(true);
                    jLabel12.setVisible(true);
                    jLabelRealTimeSpindleRPM.setVisible(true);
                    jLabelWorkPositionY.setVisible(true);
                    jButton3.setVisible(true);
                    jLabel15.setVisible(true);
                    jButton2.setVisible(true);
                    jLabel2.setVisible(true);
                    jLabel3.setVisible(true);
                    jButton1.setVisible(true);
                    jDialog2.pack();
                    jDialog2.setLocationRelativeTo(null);
                    jDialog2.setVisible(true);
                    if (tutorial) {
                        /****** FIN TUTO *****/
                        jButtonGCodeVisualize.setVisible(true);
                        jButtonGCodeBrowse.setVisible(true);
                        jButtonVisualise.setVisible(true);
                        jButtonBrowse.setVisible(true);
                        jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                        /* Elements Statut*/
                        jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                        /* Elements de Machine Control */
                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                        /* Elements de  GCode SENDER */
                        jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                        /* Elements de TabbedPane */
                        /* TAB 1*/
                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                        /* TAB 2*/
                        jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                        /* TAB 3*/
                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                        /* TAB 4*/
                        jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                    }
                    else{
                        /* Elements de Machine Control Visible*/
                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                        /* Elements Status Inivisble */
                        jLabelMachinePositionX.setVisible(false); jLabelRealTimeFeedRate.setVisible(false); jLabel14.setVisible(false); jLabelWorkPositionZ.setVisible(false); jLabelSemiAutoToolChangeStatus.setVisible(false); jLabelMachinePositionY.setVisible(false); jLabelRowsInFile7.setVisible(false); jLabelWorkPositionX.setVisible(false); jButtonResetWorkPosition.setVisible(false); jLabelMachinePositionZ.setVisible(false); jLabel12.setVisible(false); jLabelRealTimeSpindleRPM.setVisible(false); jLabelWorkPositionY.setVisible(false); jButton3.setVisible(false); jLabel15.setVisible(false); jButton2.setVisible(false); jLabel2.setVisible(false); jLabel3.setVisible(false); jButton1.setVisible(false);
                        
                        jDialogMachineAxis.pack();
                        jDialogMachineAxis.setLocationRelativeTo(null);
                        jDialogMachineAxis.setVisible(true);
                        if (tutorial) {
                            /****** FIN TUTO *****/
                            jButtonGCodeVisualize.setVisible(true);
                            jButtonGCodeBrowse.setVisible(true);
                            jButtonVisualise.setVisible(true);
                            jButtonBrowse.setVisible(true);
                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                            /* Elements Statut*/
                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                            /* Elements de Machine Control */
                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                            /* Elements de  GCode SENDER */
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            /* Elements de TabbedPane */
                            /* TAB 1*/
                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                            /* TAB 2*/
                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                            /* TAB 3*/
                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                            /* TAB 4*/
                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                        }
                        else{
                            /* Elements de Machine Control Invisible*/
                            jRadioButtonMillimeters.setVisible(false); jButtonZMinus.setVisible(false); jCheckBoxEnableKeyboardJogging.setVisible(false); jButtonZPlus.setVisible(false); jButtonXPlus.setVisible(false); jLabel4.setVisible(false); jRadioButtonInches.setVisible(false); jSliderStepSize.setVisible(false); jLabelRemoveFocus.setVisible(false); jButtonXMinus.setVisible(false); jButtonReturnToZero.setVisible(false); jSpinnerStep.setVisible(false); jButtonYPlus.setVisible(false); jButtonYMinus.setVisible(false);      
                            /* Elements de  GCode SENDER VISIBLE*/
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            jButtonGCodeVisualize.setVisible(true);jButtonGCodeBrowse.setVisible(true);jButtonVisualise.setVisible(true);jButtonBrowse.setVisible(true);
                            jDialogGSender.pack();
                            jDialogGSender.setLocationRelativeTo(null);
                            jDialogGSender.setVisible(true);
                 
                            if(tutorial){
                                /****** FIN TUTO *****/
                                jButtonVisualise.setVisible(true);
                                jButtonBrowse.setVisible(true);
                                jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                /* Elements Statut*/
                                jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                /* Elements de Machine Control */
                                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                                /* Elements de  GCode SENDER */
                                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                /* Elements de TabbedPane */
                                /* TAB 1*/
                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                /* TAB 2*/
                                jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                /* TAB 3*/
                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                /* TAB 4*/
                                jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);   
                            }
                            else{

                                /*Elements de  GCode SENDER INVISIBLE */
                                jButtonGCodeVisualize.setVisible(false);
                                jButtonGCodeBrowse.setVisible(false);
                                jButtonVisualise.setVisible(false);
                                jButtonBrowse.setVisible(false);
                                jButtonGCodePause.setVisible(false); jLabelRowsInFile3.setVisible(false); jLabelRowsInFile2.setVisible(false); jLabelRowsInFile.setVisible(false); jLabelSentRows.setVisible(false); jButtonGCodeSend.setVisible(false); jProgressBarGCodeProgress.setVisible(false); jButtonGCodeCancel.setVisible(false); jLabelRowsInFile1.setVisible(false); jLabelRowsInFile4.setVisible(false); jLabelTimeElapsed.setVisible(false); jLabelRowsInFile5.setVisible(false); jTextFieldGCodeFile.setVisible(false); jLabel5.setVisible(false); jLabelRemainingRows.setVisible(false);
                                /* Elements de TabbedPane */
                                /* TAB 1*/
                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                jDialogTab1.pack();
                                jDialogTab1.setLocationRelativeTo(null);
                                jDialogTab1.setVisible(true);
                                if(tutorial){
                                    jButtonGCodeVisualize.setVisible(true);
                                    jButtonGCodeBrowse.setVisible(true);
                                    jButtonVisualise.setVisible(true);
                                    jButtonBrowse.setVisible(true);
                                    /****** FIN TUTO *****/
                                    jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                    /* Elements Statut*/
                                    jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                    /* Elements de Machine Control */
                                    jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                    /* Elements de  GCode SENDER */
                                    jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                    /* Elements de TabbedPane */
                                    /* TAB 1*/
                                    jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                    /* TAB 2*/
                                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                    /* TAB 3*/
                                    jLabel9.setVisible(true); jLabel10.setVisible(true);
                                    /* TAB 4*/
                                    jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                }
                                else{
                                    /* Elements de TabbedPane */
                                    /* TAB 1 INVISIBLE*/
                                    jLabel7.setVisible(false); jTextAreaConsole.setVisible(false); jCheckBoxShowVerboseOutput.setVisible(false); jTextFieldCommand.setVisible(false); jButtonClearConsole.setVisible(false);
                                    /* TAB 2*/
                                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                    jDialogTab2.pack();
                                    jDialogTab2.setLocationRelativeTo(null);
                                    jDialogTab2.setVisible(true);
                                    if(tutorial){
                                        /****** FIN TUTO *****/
                                        jButtonGCodeVisualize.setVisible(true);
                                        jButtonGCodeBrowse.setVisible(true);
                                        jButtonVisualise.setVisible(true);
                                        jButtonBrowse.setVisible(true);
                                        jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                        /* Elements Statut*/
                                        jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                        /* Elements de Machine Control */
                                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                        /* Elements de  GCode SENDER */
                                        jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                        /* Elements de TabbedPane */
                                        /* TAB 1*/
                                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                        /* TAB 2*/
                                        jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                        /* TAB 3*/
                                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                                        /* TAB 4*/
                                        jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                    }
                                    else{
                                        /* TAB 2 INVISIBLE */
                                        jTableGCodeLog.setVisible(false); jButtonClearLog.setVisible(false); jCheckBoxEnableGCodeLog.setVisible(false);
                                        /* TAB 3*/
                                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                                        jDialogTab3.pack();
                                        jDialogTab3.setLocationRelativeTo(null);
                                        jDialogTab3.setVisible(true);
                                        if(tutorial){
                                            /****** FIN TUTO *****/
                                            jButtonGCodeVisualize.setVisible(true);
                                            jButtonGCodeBrowse.setVisible(true);
                                            jButtonVisualise.setVisible(true);
                                            jButtonBrowse.setVisible(true);
                                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                            /* Elements Statut*/
                                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                            /* Elements de Machine Control */
                                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                                            /* Elements de  GCode SENDER */
                                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                            /* Elements de TabbedPane */
                                            /* TAB 1*/
                                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                            /* TAB 2*/
                                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                            /* TAB 3*/
                                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                                            /* TAB 4*/
                                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);   
                                        }
                                        else{
                                            /* TAB 3 INVISIBLE */
                                            jLabel9.setVisible(false); jLabel10.setVisible(false);
                                            /* TAB 4*/
                                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                            
                                            jDialogTab4.setLocationRelativeTo(null);
                                            jDialogTab4.setVisible(true);
                                            level=2;
                                            UpdateLevel();
                                            
                                                /****** FIN TUTO *****/
                                                jButtonGCodeVisualize.setVisible(true);
                                                jButtonGCodeBrowse.setVisible(true);
                                                jButtonVisualise.setVisible(true);
                                                jButtonBrowse.setVisible(true);
                                                jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                                /* Elements Statut*/
                                                jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                                /* Elements de Machine Control */
                                                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                                /* Elements de  GCode SENDER */
                                                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                                /* Elements de TabbedPane */
                                                /* TAB 1*/
                                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                                /* TAB 2*/
                                                jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                                /* TAB 3*/
                                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                                /* TAB 4*/
                                                jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                              
                                        }

                                        
                                    }
                                    
                                }
                                
                            }
                        }
                    }
                  }
                    tutorial=true;
                    
                
        
        
        } 
    }//GEN-LAST:event_jMenuItem5ActionPerformed

    private void jButtonConnectDisconnect1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonConnectDisconnect1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonConnectDisconnect1ActionPerformed

    private void jButtonResetWorkPosition1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonResetWorkPosition1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonResetWorkPosition1ActionPerformed

    private void jLabelWorkPositionZ1MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionZ1MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionZ1MouseClicked

    private void jLabelWorkPositionX1MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionX1MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionX1MouseClicked

    private void jLabelWorkPositionY1MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionY1MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionY1MouseClicked

    private void jButton4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton4ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton4ActionPerformed

    private void jButton5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton5ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton5ActionPerformed

    private void jButton6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton6ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton6ActionPerformed

    private void jButton7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton7ActionPerformed
        jDialog1.dispose();
    }//GEN-LAST:event_jButton7ActionPerformed

    private void jButton8ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton8ActionPerformed
       tutorial=true;
        jDialog1.dispose();
    }//GEN-LAST:event_jButton8ActionPerformed

    private void jButtonCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancelActionPerformed
        tutorial=true;
        jDialog2.dispose();
    }//GEN-LAST:event_jButtonCancelActionPerformed

    private void jButtonOkActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOkActionPerformed
        jDialog2.dispose();
    }//GEN-LAST:event_jButtonOkActionPerformed

    private void jButtonCancel1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel1ActionPerformed
        tutorial=true;
        jDialogGSender.dispose();
    }//GEN-LAST:event_jButtonCancel1ActionPerformed

    private void jButtonOk1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk1ActionPerformed
        jDialogGSender.dispose();
    }//GEN-LAST:event_jButtonOk1ActionPerformed

    private void jButtonCancel4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel4ActionPerformed
        // TODO add your handling code here:
        tutorial=true;
        jDialogTab3.dispose();
    }//GEN-LAST:event_jButtonCancel4ActionPerformed

    private void jButtonOk4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk4ActionPerformed
        jDialogTab3.dispose();
    }//GEN-LAST:event_jButtonOk4ActionPerformed

    private void jButtonCancel5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel5ActionPerformed
        // TODO add your handling code here:
        tutorial=true;
        jDialogTab4.dispose();
    }//GEN-LAST:event_jButtonCancel5ActionPerformed

    private void jButtonOk5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk5ActionPerformed
        // TODO add your handling code here:
        jDialogTab4.dispose();
    }//GEN-LAST:event_jButtonOk5ActionPerformed

    private void jTextFieldGCodeFile1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jTextFieldGCodeFile1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jTextFieldGCodeFile1ActionPerformed

    private void jButtonGCodeBrowse1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeBrowse1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeBrowse1ActionPerformed

    private void jButtonGCodePause1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodePause1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodePause1ActionPerformed

    private void jButtonGCodeSend1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeSend1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeSend1ActionPerformed

    private void jButtonGCodeCancel1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeCancel1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeCancel1ActionPerformed

    private void jButtonGCodeVisualize1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeVisualize1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeVisualize1ActionPerformed

    private void jCheckBoxShowVerboseOutput1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBoxShowVerboseOutput1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jCheckBoxShowVerboseOutput1ActionPerformed

    private void jButtonClearConsole1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonClearConsole1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonClearConsole1ActionPerformed

    private void jTextFieldCommand1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jTextFieldCommand1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jTextFieldCommand1ActionPerformed

    private void jButtonOk2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk2ActionPerformed
        jDialogTab1.dispose();
    }//GEN-LAST:event_jButtonOk2ActionPerformed

    private void jButtonCancel2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel2ActionPerformed
        tutorial=true;
        jDialogTab1.dispose();
    }//GEN-LAST:event_jButtonCancel2ActionPerformed


//--------------------------Partie NONO--------------------------------
    private void jConfirmerBouttonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jConfirmerBouttonActionPerformed


        checkStatus();
    }//GEN-LAST:event_jConfirmerBouttonActionPerformed

    private void jLevelComboActionPerformed(java.awt.event.ActionEvent evt) {
        level = jLevelCombo.getSelectedIndex() + 1; //niveau de l'utilisateur entre 1 et 3
        if (level == 1){
            jMenu6.setText("Débutant");
        }
        if(level == 2){
            jMenu6.setText("Intermédiaire");
        }
        if(level == 3){
            jMenu6.setText("Expert");
        }
    }

    private void jMenuItem6ActionPerformed(java.awt.event.ActionEvent evt) {

       jMenu6.setText("Débutant");
       level=1;
       checkStatus();
    }

    private void jMenuItem7ActionPerformed(java.awt.event.ActionEvent evt) {
        jMenu6.setText("Intermédiaire");
        level=2;
        checkStatus();
    }

    private void jMenuItem8ActionPerformed(java.awt.event.ActionEvent evt) {
        jMenu6.setText("Expert");
        level=3;
        checkStatus();
    }


private void checkStatus(){
    //debutant
    if(level == 1){
        jMenuSetWorkPos.setEnabled(false);
        jMenuItemHoleCenterFinder.setEnabled(false);
        jPanel5.setEnabled(false);
        jPanelMacros.setEnabled(false);
        jPanelMachineControl.setEnabled(false);
        jMenuItemToolChangeSettings.setEnabled(false);
        jPanel6.setVisible(true);
        jRadioButtonInches.setEnabled(false);
        jRadioButtonMillimeters.setEnabled(false);
        jSpinnerStep.setEnabled(false);
        jSliderStepSize.setEnabled(false);
        jCheckBoxEnableKeyboardJogging.setEnabled(false);
        jButtonZMinus.setEnabled(false);
        jButtonZPlus.setEnabled(false);
        jButtonXPlus.setEnabled(false);
        jButtonYPlus.setEnabled(false);
        jButtonXMinus.setEnabled(false);
        jButtonYMinus.setEnabled(false);
        jButtonReturnToZero.setEnabled(false);
        jLabelRemoveFocus.setEnabled(false);
        jLabel4.setEnabled(false);
        jTabbedPane1.setEnabledAt(2,false);
}
    
   //Intermédiaire
    if(level == 2){
        jMenuSetWorkPos.setEnabled(true);
        jMenuItemHoleCenterFinder.setEnabled(true);
        jPanel5.setEnabled(true);
        jPanelMacros.setEnabled(true);
        jPanelMachineControl.setEnabled(true);
        jMenuItemToolChangeSettings.setEnabled(true);
        /* Cacher aide débutants */
        jPanel6.setVisible(true);
        jRadioButtonInches.setEnabled(true);
        jRadioButtonMillimeters.setEnabled(true);
        jSpinnerStep.setEnabled(true);
        jSliderStepSize.setEnabled(true);
        jCheckBoxEnableKeyboardJogging.setEnabled(true);
        jButtonZMinus.setEnabled(true);
        jButtonZPlus.setEnabled(true);
        jButtonXPlus.setEnabled(true);
        jButtonYPlus.setEnabled(true);
        jButtonXMinus.setEnabled(true);
        jButtonYMinus.setEnabled(true);
        jButtonReturnToZero.setEnabled(true);
        jLabelRemoveFocus.setEnabled(true);
        jLabel4.setEnabled(true);
        jTabbedPane1.setEnabledAt(2,true);

}
    
    //Expert
    if(level == 3){
        jMenuSetWorkPos.setEnabled(true);
        jMenuItemHoleCenterFinder.setEnabled(true);
        jPanel5.setEnabled(true);
        jPanelMacros.setEnabled(true);
        jPanelMachineControl.setEnabled(true);
        jMenuItemToolChangeSettings.setEnabled(true);
        /* Cacher aide débutants */
        jPanel6.setVisible(false);
        jRadioButtonInches.setEnabled(true);
        jRadioButtonMillimeters.setEnabled(true);
        jSpinnerStep.setEnabled(true);
        jSliderStepSize.setEnabled(true);
        jCheckBoxEnableKeyboardJogging.setEnabled(true);
        jButtonZMinus.setEnabled(true);
        jButtonZPlus.setEnabled(true);
        jButtonXPlus.setEnabled(true);
        jButtonYPlus.setEnabled(true);
        jButtonXMinus.setEnabled(true);
        jButtonYMinus.setEnabled(true);
        jButtonReturnToZero.setEnabled(true);
        jLabelRemoveFocus.setEnabled(true);
        jLabel4.setEnabled(true);
        jTabbedPane1.setEnabledAt(2,true);
        jDialogLevel.dispose();
        jDialog3.setLocationRelativeTo(null);
        jDialog3.setVisible(true);
    
    }
}
    

    

    private void jButtonBrowseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonBrowseActionPerformed
       final String path = SettingsManager.getLastGCodeBrowsedDirectory();
        JFileChooser fc;
        try
        {
            fc = new JFileChooser(new File(path));
            FileNameExtensionFilter filter = new FileNameExtensionFilter("GCode Files (.nc, .gcode, .tap, .gc)", "nc", "gcode", "tap", "gc");
            fc.setFileFilter(filter);
        }
        catch (Exception ex)
        {
            fc = new JFileChooser();
        }
        int returnVal = fc.showOpenDialog(this);

        if (fc.getSelectedFile() != null && returnVal == JFileChooser.APPROVE_OPTION)
        {
            File gcodeFile = fc.getSelectedFile();
            String gcodeFilePath = fc.getSelectedFile().getPath();
            jTextFieldGCodeFile.setText(gcodeFilePath);

            SettingsManager.setLastGCodeBrowsedDirectory(gcodeFile.getParent());

            // Ask the GCodeSender of the active connection handler to load the GCode File
            if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().LoadGCodeFile(gcodeFile))
            {
                jLabelRowsInFile.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsInFile()));
            }
        }
    }//GEN-LAST:event_jButtonBrowseActionPerformed

    private void jButtonVisualiseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonVisualiseActionPerformed
        try
        {
            final Chart2D chart = new Chart2D();
            // Create an ITrace: 
            ITrace2D trace = new Trace2DSimple();
            // Add the trace to the chart. This has to be done before adding points (deadlock prevention): 
            chart.addTrace(trace);

            final Queue<String> gcodeQueue = new ArrayDeque(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeQueue());
            double x = 0, y = 0, z = 0, maxX = 0, maxY = 0;

            while (gcodeQueue.size() > 0)
            {
                final GCodeCommand command = new GCodeCommand(gcodeQueue.remove());
                x = (command.getCoordinates().getX() != null) ? command.getCoordinates().getX() : x;
                y = (command.getCoordinates().getY() != null) ? command.getCoordinates().getY() : y;
                z = (command.getCoordinates().getZ() != null) ? command.getCoordinates().getZ() : z;

                if (z < 0)
                {
                    maxX = Math.max(x, maxX);
                    maxY = Math.max(y, maxY);
                    trace.addPoint(x, y);
                }
            }

            chart.getAxisX().setRangePolicy(new RangePolicyFixedViewport(new Range(0, Math.max(maxY, maxX))));
            chart.getAxisY().setRangePolicy(new RangePolicyFixedViewport(new Range(0, Math.max(maxY, maxX))));

            // Make it visible:
            // Create a frame.
            final JFrame frame = new JFrame(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeFile().getName());
            // add the chart to the frame: 
            frame.getContentPane().add(chart);
            frame.setSize(600, 600);
            frame.setVisible(true);
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jButtonVisualiseActionPerformed


    private void jButton9ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton9ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton9ActionPerformed

    private void jButton10ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton10ActionPerformed
       this.dispose();
    }//GEN-LAST:event_jButton10ActionPerformed

    private void jButton11ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton11ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton11ActionPerformed

    private void jMenu6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenu6ActionPerformed
    
    }//GEN-LAST:event_jMenu6ActionPerformed

    private void jMenu6StateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_jMenu6StateChanged
    
    }//GEN-LAST:event_jMenu6StateChanged

    private void jComboBox2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jComboBox2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jComboBox2ActionPerformed

    private void jButtonConnectDisconnect7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonConnectDisconnect7ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonConnectDisconnect7ActionPerformed

    private void jMenuItem1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem1ActionPerformed
        GCode_Tutorial tuto = new GCode_Tutorial();
        tuto.setLocationRelativeTo(null);
        tuto.setVisible(true);
    }//GEN-LAST:event_jMenuItem1ActionPerformed

    private void jMenuItem10ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem10ActionPerformed
        GCode_Conversion conversion = new GCode_Conversion();
        conversion.setLocationRelativeTo(null);
        conversion.setVisible(true);
        
    }//GEN-LAST:event_jMenuItem10ActionPerformed

    private void jMenuItem9ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem9ActionPerformed
        
        /******** TUTO COMPLET *********/
        jDialogMiseEnPlace.pack();
        jDialogMiseEnPlace.setLocationRelativeTo(null);
        jDialogMiseEnPlace.setVisible(true);
    }//GEN-LAST:event_jMenuItem9ActionPerformed

    private void jButton13ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton13ActionPerformed
        jDialogZeroAxes.setVisible(true);
        jDialogZeroAxes.setLocationRelativeTo(null);
        jDialogMiseEnPlace.dispose();
        jButton14.requestFocus();
    }//GEN-LAST:event_jButton13ActionPerformed

    private void jButton12ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton12ActionPerformed
        // TODO add your handling code here:
        frmSetWorkPosition frm = new frmSetWorkPosition(this, true);
        frm.setVisible(true);
        frm.setLocationRelativeTo(null);
        
        jButton13.setEnabled(true);
        jButton13.requestFocus();
        jButton13.setSelected(true);
        jButton12.setSelected(false);
        
    }//GEN-LAST:event_jButton12ActionPerformed

    private void jButton14ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton14ActionPerformed
        // TODO add your handling code here:
        try
        {
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendGCodeCommand(new GCodeCommand("$H"));
        }
        catch (Exception ex)
        {

        }        
    }//GEN-LAST:event_jButton14ActionPerformed

    private void jButton24ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton24ActionPerformed
        // TODO add your handling code here:
        jDialogImportGCode.setLocationRelativeTo(null);
        jDialogImportGCode.setVisible(true);
        jDialogZeroAxes.dispose();
        
    }//GEN-LAST:event_jButton24ActionPerformed

    private void jButton25ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton25ActionPerformed
        // TODO add your handling code here:
        frmZAxisTouchProbe frm = new frmZAxisTouchProbe(this, true);
        frm.setVisible(true);
        
        getInfo();
        if(process_fini==2){
        jButton24.setEnabled(true);
        jButton24.requestFocus();
        jButton24.setSelected(true);
        
        jButton14.setEnabled(false);
        }
    }//GEN-LAST:event_jButton25ActionPerformed

    private void jMenu2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenu2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jMenu2ActionPerformed

    private void jButton27ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton27ActionPerformed
        // TODO add your handling code here:
        jDialogImportGCode.dispose();
    }//GEN-LAST:event_jButton27ActionPerformed

    private void jButtonGCodeBrowse2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeBrowse2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeBrowse2ActionPerformed

    private void jTextFieldGCodeFile2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jTextFieldGCodeFile2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jTextFieldGCodeFile2ActionPerformed

    private void jButtonGCodePause2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodePause2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodePause2ActionPerformed

    private void jButtonGCodeSend2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeSend2ActionPerformed
        // TODO add your handling code here:
        boolean startCycle = true;
        if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getX() != 0
                || ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getY() != 0
                || ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getWorkPosition().getZ() != 0)
        {
            startCycle = false;
            int answer = JOptionPane.showConfirmDialog(
                    null,
                    "La position de travail n'est pas 0,0,0.\nVoulez vous démarrer le cycle G Code?",
                    "Position de travail n'est pas 0,0,0",
                    JOptionPane.YES_NO_OPTION);

            startCycle = (answer == JOptionPane.YES_OPTION);
        }

        if (startCycle)
        {
            EnableOrDisableComponentsWhenMachineIsCyclingGCode(true);
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().StartSendingGCode();
            jButton27.setEnabled(true);
        }
        
    }//GEN-LAST:event_jButtonGCodeSend2ActionPerformed

    private void jButtonGCodeCancel2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeCancel2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeCancel2ActionPerformed

    private void jButtonGCodeVisualize2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonGCodeVisualize2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonGCodeVisualize2ActionPerformed

    private void jButtonBrowse1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonBrowse1ActionPerformed
        // TODO add your handling code here:
        final String path = SettingsManager.getLastGCodeBrowsedDirectory();
        JFileChooser fc;
        try
        {
            fc = new JFileChooser(new File(path));
            FileNameExtensionFilter filter = new FileNameExtensionFilter("GCode Files (.nc, .gcode, .tap, .gc)", "nc", "gcode", "tap", "gc");
            fc.setFileFilter(filter);
        }
        catch (Exception ex)
        {
            fc = new JFileChooser();
        }
        int returnVal = fc.showOpenDialog(this);

        if (fc.getSelectedFile() != null && returnVal == JFileChooser.APPROVE_OPTION)
        {
            File gcodeFile = fc.getSelectedFile();
            String gcodeFilePath = fc.getSelectedFile().getPath();
            jTextFieldGCodeFile.setText(gcodeFilePath);

            SettingsManager.setLastGCodeBrowsedDirectory(gcodeFile.getParent());

            // Ask the GCodeSender of the active connection handler to load the GCode File
            if (ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().LoadGCodeFile(gcodeFile))
            {
                jLabelRowsInFile.setText(String.valueOf(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getRowsInFile()));
            }
        }
    }//GEN-LAST:event_jButtonBrowse1ActionPerformed

    private void jButtonVisualise1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonVisualise1ActionPerformed
        // TODO add your handling code here:
        try
        {
            final Chart2D chart = new Chart2D();
            // Create an ITrace: 
            ITrace2D trace = new Trace2DSimple();
            // Add the trace to the chart. This has to be done before adding points (deadlock prevention): 
            chart.addTrace(trace);

            final Queue<String> gcodeQueue = new ArrayDeque(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeQueue());
            double x = 0, y = 0, z = 0, maxX = 0, maxY = 0;

            while (gcodeQueue.size() > 0)
            {
                final GCodeCommand command = new GCodeCommand(gcodeQueue.remove());
                x = (command.getCoordinates().getX() != null) ? command.getCoordinates().getX() : x;
                y = (command.getCoordinates().getY() != null) ? command.getCoordinates().getY() : y;
                z = (command.getCoordinates().getZ() != null) ? command.getCoordinates().getZ() : z;

                if (z < 0)
                {
                    maxX = Math.max(x, maxX);
                    maxY = Math.max(y, maxY);
                    trace.addPoint(x, y);
                }
            }

            chart.getAxisX().setRangePolicy(new RangePolicyFixedViewport(new Range(0, Math.max(maxY, maxX))));
            chart.getAxisY().setRangePolicy(new RangePolicyFixedViewport(new Range(0, Math.max(maxY, maxX))));

            // Make it visible:
            // Create a frame.
            final JFrame frame = new JFrame(ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().getGCodeFile().getName());
            // add the chart to the frame: 
            frame.getContentPane().add(chart);
            frame.setSize(600, 600);
            frame.setVisible(true);
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jButtonVisualise1ActionPerformed

    private void jRadioButtonInches1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRadioButtonInches1ActionPerformed
        // Inches Selected!
        jRadioButtonMillimeters.setSelected(false);
        fJoggingUnits = EUnits.Imperial;
    }//GEN-LAST:event_jRadioButtonInches1ActionPerformed

    private void jRadioButtonMillimeters1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRadioButtonMillimeters1ActionPerformed
        // Millimeters Selected!
        jRadioButtonInches.setSelected(false);
        fJoggingUnits = EUnits.Metric;
    }//GEN-LAST:event_jRadioButtonMillimeters1ActionPerformed

    private void jButtonYMinus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonYMinus1ActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Y-", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonYMinus1ActionPerformed

    private void jButtonXMinus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonXMinus1ActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "X-", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonXMinus1ActionPerformed

    private void jButtonYPlus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonYPlus1ActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Y", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonYPlus1ActionPerformed

    private void jButtonXPlus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonXPlus1ActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "X", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonXPlus1ActionPerformed

    private void jButtonZPlus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonZPlus1ActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Z", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonZPlus1ActionPerformed

    private void jButtonZMinus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonZMinus1ActionPerformed
        double stepValue = (double) jSpinnerStep.getValue();
        Process_Jogging p = new Process_Jogging(null, "Z-", stepValue, fJoggingUnits);
        p.Execute();
        p.Dispose();
    }//GEN-LAST:event_jButtonZMinus1ActionPerformed

    private void jCheckBoxEnableKeyboardJogging1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBoxEnableKeyboardJogging1ActionPerformed
         try
        {
            SettingsManager.setIsKeyboardJoggingEnabled(jCheckBoxEnableKeyboardJogging.isSelected());
        }
        catch (Exception ex)
        {

        }
    }//GEN-LAST:event_jCheckBoxEnableKeyboardJogging1ActionPerformed

    private void jLabelRemoveFocus1MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelRemoveFocus1MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelRemoveFocus1MouseClicked

    private void jButtonReturnToZero1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonReturnToZero1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonReturnToZero1ActionPerformed

    private void jSliderStepSize1StateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_jSliderStepSize1StateChanged
        int value = jSliderStepSize.getValue();
        switch (value)
        {
            case 0:
                jSpinnerStep.setValue(0.001);
                break;

            case 1:
                jSpinnerStep.setValue(0.01);
                break;

            case 2:
                jSpinnerStep.setValue(0.1);
                break;

            case 3:
                jSpinnerStep.setValue(1.0);
                break;

            case 4:
                jSpinnerStep.setValue(10.0);
                break;

            case 5:
                jSpinnerStep.setValue(100.0);
                break;

        }
    }//GEN-LAST:event_jSliderStepSize1StateChanged

    private void formWindowActivated(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowActivated
        // TODO add your handling code here:
        
    }//GEN-LAST:event_formWindowActivated

    private void formWindowGainedFocus(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowGainedFocus

    }//GEN-LAST:event_formWindowGainedFocus

    private void formWindowOpened(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowOpened
        if(tutorial){    
        tutorial=false;
         /***********DEBUT DU TUTORIEL *****************/
         int verified_level =0;
        if(jMenu6.getText()=="Level"){
        // Choix du level
        jDialogLevel.pack();
        jDialogLevel.setVisible(true);

        verified_level = jLevelCombo.getSelectedIndex() + 1;
        UpdateLevel();
        jButton7.requestFocus();
        jButtonOk.requestFocus();  
        jButtonOk1.requestFocus();  
        jButtonOk2.requestFocus();  
        jButtonOk3.requestFocus();  
        jButtonOk4.requestFocus();  
   

        verified_level = jLevelCombo.getSelectedIndex() + 1 ;
        }
        else{
            if(null!=jMenu6.getText())switch (jMenu6.getText()) {
                case "Expert":
                    verified_level = 3;
                    break;
                case "Intermédiaire":
                    verified_level = 2;
                    break;
                case "Débutant":
                    verified_level = 1;
                    break;
                default:
                    break;
            }
        }


               

            switch (verified_level) {
                case 1:
                    // Vider tous les Panels sauf CONNECTION
                    /* Elements de Machine Status */
                    jButtonVisualise.setVisible(false);
                    jButtonBrowse.setVisible(false);
                    jLabelMachinePositionX.setVisible(false);
                    jLabelRealTimeFeedRate.setVisible(false);
                    jLabel14.setVisible(false);
                    jLabelWorkPositionZ.setVisible(false);
                    jLabelSemiAutoToolChangeStatus.setVisible(false);
                    jLabelMachinePositionY.setVisible(false);
                    jLabelRowsInFile7.setVisible(false);
                    jLabelWorkPositionX.setVisible(false);
                    jButtonResetWorkPosition.setVisible(false);
                    jLabelMachinePositionZ.setVisible(false);
                    jLabel12.setVisible(false);
                    jLabelRealTimeSpindleRPM.setVisible(false);
                    jLabelWorkPositionY.setVisible(false);
                    jButton3.setVisible(false);
                    jLabel15.setVisible(false);
                    jButton2.setVisible(false);
                    jLabel2.setVisible(false);
                    jLabel3.setVisible(false);
                    jButton1.setVisible(false);
                    /* Elements de Machine Control */
                    jRadioButtonMillimeters.setVisible(false);
                    jButtonZMinus.setVisible(false);
                    jCheckBoxEnableKeyboardJogging.setVisible(false);
                    jButtonZPlus.setVisible(false);
                    jButtonXPlus.setVisible(false);
                    jLabel4.setVisible(false);
                    jRadioButtonInches.setVisible(false);
                    jSliderStepSize.setVisible(false);
                    jLabelRemoveFocus.setVisible(false);
                    jButtonXMinus.setVisible(false);
                    jButtonReturnToZero.setVisible(false);
                    jSpinnerStep.setVisible(false);
                    jButtonYPlus.setVisible(false);
                    jButtonYMinus.setVisible(false);
                    /* Elements de  GCode SENDER */
                    jButtonGCodePause.setVisible(false);
                    jLabelRowsInFile3.setVisible(false);
                    jLabelRowsInFile2.setVisible(false);
                    jLabelRowsInFile.setVisible(false);
                    jLabelSentRows.setVisible(false);
                    jButtonGCodeSend.setVisible(false);
                    jProgressBarGCodeProgress.setVisible(false);
                    jButtonGCodeCancel.setVisible(false);
                    jLabelRowsInFile1.setVisible(false);
                    jLabelRowsInFile4.setVisible(false);
                    jLabelTimeElapsed.setVisible(false);
                    jLabelRowsInFile5.setVisible(false);
                    jTextFieldGCodeFile.setVisible(false);
                    jLabel5.setVisible(false);
                    jLabelRemainingRows.setVisible(false);
                    /* Elements de TabbedPane */
                    /* TAB 1*/
                    jLabel7.setVisible(false);
                    jTextAreaConsole.setVisible(false);
                    jCheckBoxShowVerboseOutput.setVisible(false);
                    jTextFieldCommand.setVisible(false);
                    jButtonClearConsole.setVisible(false);
                    /* TAB 2*/
                    jTableGCodeLog.setVisible(false);
                    jButtonClearLog.setVisible(false);
                    jCheckBoxEnableGCodeLog.setVisible(false);
                    /* TAB 3*/
                    jLabel9.setVisible(false);
                    jLabel10.setVisible(false);
                    /* TAB 4*/
                    jLabel16.setVisible(false);
                    jLabelMachineHomePosition.setVisible(false);
                    jLabelLastStatusUpdate.setVisible(false);
                    jLabel17.setVisible(false);
                    jButtonGCodeVisualize.setVisible(false);
                    jButtonGCodeBrowse.setVisible(false);
                    jDialog1.pack();
                    jDialog1.setLocationRelativeTo(null);
                    jDialog1.setVisible(true);
                    if(tutorial){
                        
                    /***** FIN DU TUTO *******/
                    /*Element Connection*/
                    jButtonGCodeVisualize.setVisible(true);jButtonGCodeBrowse.setVisible(true);jButtonVisualise.setVisible(true);jButtonBrowse.setVisible(true);
                    jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                    /* Elements Statut*/
                    jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                    /* Elements de Machine Control */
                    jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                    /* Elements de  GCode SENDER */
                    jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                    /* Elements de TabbedPane */
                    /* TAB 1*/
                    jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                    /* TAB 2*/
                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                     /* TAB 3*/
                    jLabel9.setVisible(true); jLabel10.setVisible(true);
                     /* TAB 4*/
                    jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                                                   
                    }else{
                    /* Elements de Connection Invisible*/
                    jButtonSoftReset.setVisible(false);
                    jLabelMachineX1.setVisible(false);
                    jButtonKillAlarm.setVisible(false);
                    jButtonConnectDisconnect1.setVisible(false);
                    jLabelActiveState.setVisible(false);
                    /* Elements de Machine Statuts Visible */
                    jLabelMachinePositionX.setVisible(true);
                    jLabelRealTimeFeedRate.setVisible(true);
                    jLabel14.setVisible(true);
                    jLabelWorkPositionZ.setVisible(true);
                    jLabelSemiAutoToolChangeStatus.setVisible(true);
                    jLabelMachinePositionY.setVisible(true);
                    jLabelRowsInFile7.setVisible(true);
                    jLabelWorkPositionX.setVisible(true);
                    jButtonResetWorkPosition.setVisible(true);
                    jLabelMachinePositionZ.setVisible(true);
                    jLabel12.setVisible(true);
                    jLabelRealTimeSpindleRPM.setVisible(true);
                    jLabelWorkPositionY.setVisible(true);
                    jButton3.setVisible(true);
                    jLabel15.setVisible(true);
                    jButton2.setVisible(true);
                    jLabel2.setVisible(true);
                    jLabel3.setVisible(true);
                    jButton1.setVisible(true);
                    jDialog2.pack();
                    jDialog2.setLocationRelativeTo(null);
                    jDialog2.setVisible(true);
                    if (tutorial) {
                        /****** FIN TUTO *****/
                        jButtonGCodeVisualize.setVisible(true);
                        jButtonGCodeBrowse.setVisible(true);
                        jButtonVisualise.setVisible(true);
                        jButtonBrowse.setVisible(true);
                        jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                        /* Elements Statut*/
                        jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                        /* Elements de Machine Control */
                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                        /* Elements de  GCode SENDER */
                        jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                        /* Elements de TabbedPane */
                        /* TAB 1*/
                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                        /* TAB 2*/
                        jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                        /* TAB 3*/
                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                        /* TAB 4*/
                        jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                    }
                    else{
                        /* Elements de Machine Control Visible*/
                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                        /* Elements Status Inivisble */
                        jLabelMachinePositionX.setVisible(false); jLabelRealTimeFeedRate.setVisible(false); jLabel14.setVisible(false); jLabelWorkPositionZ.setVisible(false); jLabelSemiAutoToolChangeStatus.setVisible(false); jLabelMachinePositionY.setVisible(false); jLabelRowsInFile7.setVisible(false); jLabelWorkPositionX.setVisible(false); jButtonResetWorkPosition.setVisible(false); jLabelMachinePositionZ.setVisible(false); jLabel12.setVisible(false); jLabelRealTimeSpindleRPM.setVisible(false); jLabelWorkPositionY.setVisible(false); jButton3.setVisible(false); jLabel15.setVisible(false); jButton2.setVisible(false); jLabel2.setVisible(false); jLabel3.setVisible(false); jButton1.setVisible(false);
                        
                        jDialogMachineAxis.pack();
                        jDialogMachineAxis.setLocationRelativeTo(null);
                        jDialogMachineAxis.setVisible(true);
                        if (tutorial) {
                            /****** FIN TUTO *****/
                            jButtonGCodeVisualize.setVisible(true);
                            jButtonGCodeBrowse.setVisible(true);
                            jButtonVisualise.setVisible(true);
                            jButtonBrowse.setVisible(true);
                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                            /* Elements Statut*/
                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                            /* Elements de Machine Control */
                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                            /* Elements de  GCode SENDER */
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            /* Elements de TabbedPane */
                            /* TAB 1*/
                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                            /* TAB 2*/
                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                            /* TAB 3*/
                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                            /* TAB 4*/
                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                        }
                        else{
                            /* Elements de Machine Control Invisible*/
                            jRadioButtonMillimeters.setVisible(false); jButtonZMinus.setVisible(false); jCheckBoxEnableKeyboardJogging.setVisible(false); jButtonZPlus.setVisible(false); jButtonXPlus.setVisible(false); jLabel4.setVisible(false); jRadioButtonInches.setVisible(false); jSliderStepSize.setVisible(false); jLabelRemoveFocus.setVisible(false); jButtonXMinus.setVisible(false); jButtonReturnToZero.setVisible(false); jSpinnerStep.setVisible(false); jButtonYPlus.setVisible(false); jButtonYMinus.setVisible(false);      
                            /* Elements de  GCode SENDER VISIBLE*/
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            jButtonGCodeVisualize.setVisible(true);jButtonGCodeBrowse.setVisible(true);jButtonVisualise.setVisible(true);jButtonBrowse.setVisible(true);
                            jDialogGSender.pack();
                            jDialogGSender.setLocationRelativeTo(null);
                            jDialogGSender.setVisible(true);
                 
                            if(tutorial){
                                /****** FIN TUTO *****/
                                jButtonVisualise.setVisible(true);
                                jButtonBrowse.setVisible(true);
                                jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                /* Elements Statut*/
                                jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                /* Elements de Machine Control */
                                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                                /* Elements de  GCode SENDER */
                                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                /* Elements de TabbedPane */
                                /* TAB 1*/
                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                /* TAB 2*/
                                jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                /* TAB 3*/
                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                /* TAB 4*/
                                jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);   
                            }
                            else{

                                /*Elements de  GCode SENDER INVISIBLE */
                                jButtonGCodeVisualize.setVisible(false);
                                jButtonGCodeBrowse.setVisible(false);
                                jButtonVisualise.setVisible(false);
                                jButtonBrowse.setVisible(false);
                                jButtonGCodePause.setVisible(false); jLabelRowsInFile3.setVisible(false); jLabelRowsInFile2.setVisible(false); jLabelRowsInFile.setVisible(false); jLabelSentRows.setVisible(false); jButtonGCodeSend.setVisible(false); jProgressBarGCodeProgress.setVisible(false); jButtonGCodeCancel.setVisible(false); jLabelRowsInFile1.setVisible(false); jLabelRowsInFile4.setVisible(false); jLabelTimeElapsed.setVisible(false); jLabelRowsInFile5.setVisible(false); jTextFieldGCodeFile.setVisible(false); jLabel5.setVisible(false); jLabelRemainingRows.setVisible(false);
                                /* Elements de TabbedPane */
                                /* TAB 1*/
                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                jDialogTab1.pack();
                                jDialogTab1.setLocationRelativeTo(null);
                                jDialogTab1.setVisible(true);
                                if(tutorial){
                                    jButtonGCodeVisualize.setVisible(true);
                                    jButtonGCodeBrowse.setVisible(true);
                                    jButtonVisualise.setVisible(true);
                                    jButtonBrowse.setVisible(true);
                                    /****** FIN TUTO *****/
                                    jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                    /* Elements Statut*/
                                    jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                    /* Elements de Machine Control */
                                    jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                    /* Elements de  GCode SENDER */
                                    jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                    /* Elements de TabbedPane */
                                    /* TAB 1*/
                                    jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                    /* TAB 2*/
                                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                    /* TAB 3*/
                                    jLabel9.setVisible(true); jLabel10.setVisible(true);
                                    /* TAB 4*/
                                    jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                }
                                else{
                                    /* Elements de TabbedPane */
                                    /* TAB 1 INVISIBLE*/
                                    jLabel7.setVisible(false); jTextAreaConsole.setVisible(false); jCheckBoxShowVerboseOutput.setVisible(false); jTextFieldCommand.setVisible(false); jButtonClearConsole.setVisible(false);
                                    /* TAB 2*/
                                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                    jDialogTab2.pack();
                                    jDialogTab2.setLocationRelativeTo(null);
                                    jDialogTab2.setVisible(true);
                                    if(tutorial){
                                        /****** FIN TUTO *****/
                                        jButtonGCodeVisualize.setVisible(true);
                                        jButtonGCodeBrowse.setVisible(true);
                                        jButtonVisualise.setVisible(true);
                                        jButtonBrowse.setVisible(true);
                                        jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                        /* Elements Statut*/
                                        jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                        /* Elements de Machine Control */
                                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                        /* Elements de  GCode SENDER */
                                        jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                        /* Elements de TabbedPane */
                                        /* TAB 1*/
                                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                        /* TAB 2*/
                                        jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                        /* TAB 3*/
                                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                                        /* TAB 4*/
                                        jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                    }
                                    else{
                                        /* TAB 2 INVISIBLE */
                                        jTableGCodeLog.setVisible(false); jButtonClearLog.setVisible(false); jCheckBoxEnableGCodeLog.setVisible(false);
                                        /* TAB 3*/
                                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                                        jDialogTab3.pack();
                                        jDialogTab3.setLocationRelativeTo(null);
                                        jDialogTab3.setVisible(true);
                                        if(tutorial){
                                            /****** FIN TUTO *****/
                                            jButtonGCodeVisualize.setVisible(true);
                                            jButtonGCodeBrowse.setVisible(true);
                                            jButtonVisualise.setVisible(true);
                                            jButtonBrowse.setVisible(true);
                                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                            /* Elements Statut*/
                                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                            /* Elements de Machine Control */
                                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                                            /* Elements de  GCode SENDER */
                                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                            /* Elements de TabbedPane */
                                            /* TAB 1*/
                                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                            /* TAB 2*/
                                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                            /* TAB 3*/
                                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                                            /* TAB 4*/
                                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);   
                                        }
                                        else{
                                            /* TAB 3 INVISIBLE */
                                            jLabel9.setVisible(false); jLabel10.setVisible(false);
                                            /* TAB 4*/
                                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                            
                                            jDialogTab4.setLocationRelativeTo(null);
                                            jDialogTab4.setVisible(true);
                                            level=2;
                                            UpdateLevel();
                                            
                                                /****** FIN TUTO *****/
                                                jButtonGCodeVisualize.setVisible(true);
                                                jButtonGCodeBrowse.setVisible(true);
                                                jButtonVisualise.setVisible(true);
                                                jButtonBrowse.setVisible(true);
                                                jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                                /* Elements Statut*/
                                                jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                                /* Elements de Machine Control */
                                                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                                /* Elements de  GCode SENDER */
                                                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                                /* Elements de TabbedPane */
                                                /* TAB 1*/
                                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                                /* TAB 2*/
                                                jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                                /* TAB 3*/
                                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                                /* TAB 4*/
                                                jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                              
                                        }

                                        
                                    }
                                    
                                }
                                
                            }
                        }
                    }
                  }
                    tutorial=true;
                    break;
                case 2:
                    jDialogAskTuto.setLocationRelativeTo(null);
                    jDialogAskTuto.setVisible(true);
                    if(tutorial==false){
                         JDialogSecuriter.setLocationRelativeTo(null);
                         JDialogSecuriter.setVisible(true);
                        
                         
                    }
                    else{
                    
                    
                        /****** FIN TUTO *****/
                        jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                        /* Elements Statut*/
                        jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                        /* Elements de Machine Control */
                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                        /* Elements de  GCode SENDER */
                        jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                        /* Elements de TabbedPane */
                        /* TAB 1*/
                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                        /* TAB 2*/
                        jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                        /* TAB 3*/
                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                        /* TAB 4*/
                        jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                      if (tutorial) {
                            /****** FIN TUTO *****/
                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                            /* Elements Statut*/
                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                            /* Elements de Machine Control */
                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                            /* Elements de  GCode SENDER */
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            /* Elements de TabbedPane */
                            /* TAB 1*/
                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                            /* TAB 2*/
                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                            /* TAB 3*/
                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                            /* TAB 4*/
                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                        }  
                    
                    else{
                        /* Elements de Machine Control Visible*/
                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                        /* Elements Status Inivisble */
                        jLabelMachinePositionX.setVisible(false); jLabelRealTimeFeedRate.setVisible(false); jLabel14.setVisible(false); jLabelWorkPositionZ.setVisible(false); jLabelSemiAutoToolChangeStatus.setVisible(false); jLabelMachinePositionY.setVisible(false); jLabelRowsInFile7.setVisible(false); jLabelWorkPositionX.setVisible(false); jButtonResetWorkPosition.setVisible(false); jLabelMachinePositionZ.setVisible(false); jLabel12.setVisible(false); jLabelRealTimeSpindleRPM.setVisible(false); jLabelWorkPositionY.setVisible(false); jButton3.setVisible(false); jLabel15.setVisible(false); jButton2.setVisible(false); jLabel2.setVisible(false); jLabel3.setVisible(false); jButton1.setVisible(false);
                        
                        jDialogGSender.pack();
                        jDialogGSender.setLocationRelativeTo(null);
                        jDialogGSender.setVisible(true);
                        if (tutorial) {
                            /****** FIN TUTO *****/
                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                            /* Elements Statut*/
                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                            /* Elements de Machine Control */
                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                            /* Elements de  GCode SENDER */
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            /* Elements de TabbedPane */
                            /* TAB 1*/
                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                            /* TAB 2*/
                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                            /* TAB 3*/
                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                            /* TAB 4*/
                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                        }
                        else{
                            /* Elements de Machine Control Invisible*/
                            jRadioButtonMillimeters.setVisible(false); jButtonZMinus.setVisible(false); jCheckBoxEnableKeyboardJogging.setVisible(false); jButtonZPlus.setVisible(false); jButtonXPlus.setVisible(false); jLabel4.setVisible(false); jRadioButtonInches.setVisible(false); jSliderStepSize.setVisible(false); jLabelRemoveFocus.setVisible(false); jButtonXMinus.setVisible(false); jButtonReturnToZero.setVisible(false); jSpinnerStep.setVisible(false); jButtonYPlus.setVisible(false); jButtonYMinus.setVisible(false);      
                            /* Elements de  GCode SENDER VISIBLE*/
                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                            jDialogGSender.pack();
                            jDialogGSender.setLocationRelativeTo(null);
                            jDialogGSender.setVisible(true);
                            if(tutorial){
                                /****** FIN TUTO *****/
                                jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                /* Elements Statut*/
                                jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                /* Elements de Machine Control */
                                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                                /* Elements de  GCode SENDER */
                                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                /* Elements de TabbedPane */
                                /* TAB 1*/
                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                /* TAB 2*/
                                jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                /* TAB 3*/
                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                /* TAB 4*/
                                jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);   
                            }
                            else{

                                /*Elements de  GCode SENDER INVISIBLE */
                                jButtonGCodePause.setVisible(false); jLabelRowsInFile3.setVisible(false); jLabelRowsInFile2.setVisible(false); jLabelRowsInFile.setVisible(false); jLabelSentRows.setVisible(false); jButtonGCodeSend.setVisible(false); jProgressBarGCodeProgress.setVisible(false); jButtonGCodeCancel.setVisible(false); jLabelRowsInFile1.setVisible(false); jLabelRowsInFile4.setVisible(false); jLabelTimeElapsed.setVisible(false); jLabelRowsInFile5.setVisible(false); jTextFieldGCodeFile.setVisible(false); jLabel5.setVisible(false); jLabelRemainingRows.setVisible(false);
                                /* Elements de TabbedPane */
                                /* TAB 1*/
                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                jDialogTab1.pack();
                                jDialogTab1.setLocationRelativeTo(null);
                                jDialogTab1.setVisible(true);
                                if(tutorial){
                                    /****** FIN TUTO *****/
                                    jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                    /* Elements Statut*/
                                    jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                    /* Elements de Machine Control */
                                    jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                    /* Elements de  GCode SENDER */
                                    jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                    /* Elements de TabbedPane */
                                    /* TAB 1*/
                                    jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                    /* TAB 2*/
                                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                    /* TAB 3*/
                                    jLabel9.setVisible(true); jLabel10.setVisible(true);
                                    /* TAB 4*/
                                    jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                }
                                else{
                                    /* Elements de TabbedPane */
                                    /* TAB 1 INVISIBLE*/
                                    jLabel7.setVisible(false); jTextAreaConsole.setVisible(false); jCheckBoxShowVerboseOutput.setVisible(false); jTextFieldCommand.setVisible(false); jButtonClearConsole.setVisible(false);
                                    /* TAB 2*/
                                    jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                    jDialogTab2.pack();
                                    jDialogTab2.setLocationRelativeTo(null);
                                    jDialogTab2.setVisible(true);
                                    if(tutorial){
                                        /****** FIN TUTO *****/
                                        jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                        /* Elements Statut*/
                                        jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                        /* Elements de Machine Control */
                                        jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                        /* Elements de  GCode SENDER */
                                        jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                        /* Elements de TabbedPane */
                                        /* TAB 1*/
                                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                        /* TAB 2*/
                                        jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                        /* TAB 3*/
                                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                                        /* TAB 4*/
                                        jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                    }
                                    else{
                                        /* TAB 2 INVISIBLE */
                                        jTableGCodeLog.setVisible(false); jButtonClearLog.setVisible(false); jCheckBoxEnableGCodeLog.setVisible(false);
                                        /* TAB 3*/
                                        jLabel9.setVisible(true); jLabel10.setVisible(true);
                                        jDialogTab3.pack();
                                        jDialogTab3.setLocationRelativeTo(null);
                                        jDialogTab3.setVisible(true);
                                        if(tutorial){
                                            /****** FIN TUTO *****/
                                            jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                            /* Elements Statut*/
                                            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                            /* Elements de Machine Control */
                                            jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                                            /* Elements de  GCode SENDER */
                                            jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                            /* Elements de TabbedPane */
                                            /* TAB 1*/
                                            jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                            /* TAB 2*/
                                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                            /* TAB 3*/
                                            jLabel9.setVisible(true); jLabel10.setVisible(true);
                                            /* TAB 4*/
                                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);   
                                        }
                                        else{
                                            /* TAB 3 INVISIBLE */
                                            jLabel9.setVisible(false); jLabel10.setVisible(false);
                                            /* TAB 4*/
                                            jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                            
                                            jDialogTab4.setLocationRelativeTo(null);
                                            jDialogTab4.setVisible(true);
                                            level=3;
                                            UpdateLevel();
                                                /****** FIN TUTO *****/
                                                jButtonKillAlarm.setVisible(true); jButtonSoftReset.setVisible(true); jLabelMachineX1.setVisible(true); jLabelActiveState.setVisible(true); jButtonConnectDisconnect1.setVisible(true);
                                                /* Elements Statut*/
                                                jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
                                                /* Elements de Machine Control */
                                                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);
                                                /* Elements de  GCode SENDER */
                                                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                                                /* Elements de TabbedPane */
                                                /* TAB 1*/
                                                jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                                                /* TAB 2*/
                                                jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                                                /* TAB 3*/
                                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                                /* TAB 4*/
                                                jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                               
                                        }

                                        
                                    }

                                }
                                
                            }
                            
                        }

                    }
                    }
                    JDialogSecuriter.setLocationRelativeTo(null);
                    JDialogSecuriter.setVisible(true);
                    
                    
                    tutorial=true;
                    break;
                case 3:
                    /***** FIN DU TUTO *******/
                    /*Element Connection*/
                    jButtonKillAlarm.setVisible(true);
                    jButtonSoftReset.setVisible(true);
                    jLabelMachineX1.setVisible(true);
                    jLabelActiveState.setVisible(true);
                    jButtonConnectDisconnect1.setVisible(true);
                    /* Elements Statut*/
                    jLabelMachinePositionX.setVisible(true);
                    jLabelRealTimeFeedRate.setVisible(true);
                    jLabel14.setVisible(true);
                    jLabelWorkPositionZ.setVisible(true);
                    jLabelSemiAutoToolChangeStatus.setVisible(true);
                    jLabelMachinePositionY.setVisible(true);
                    jLabelRowsInFile7.setVisible(true);
                    jLabelWorkPositionX.setVisible(true);
                    jButtonResetWorkPosition.setVisible(true);
                    jLabelMachinePositionZ.setVisible(true);
                    jLabel12.setVisible(true);
                    jLabelRealTimeSpindleRPM.setVisible(true);
                    jLabelWorkPositionY.setVisible(true);
                    jButton3.setVisible(true);
                    jLabel15.setVisible(true);
                    jButton2.setVisible(true);
                    jLabel2.setVisible(true);
                    jLabel3.setVisible(true);
                    jButton1.setVisible(true);
                    /* Elements de Machine Control */
                    jRadioButtonMillimeters.setVisible(true);
                    jButtonZMinus.setVisible(true);
                    jCheckBoxEnableKeyboardJogging.setVisible(true);
                    jButtonZPlus.setVisible(true);
                    jButtonXPlus.setVisible(true);
                    jLabel4.setVisible(true);
                    jRadioButtonInches.setVisible(true);
                    jSliderStepSize.setVisible(true);
                    jLabelRemoveFocus.setVisible(true);
                    jButtonXMinus.setVisible(true);
                    jButtonReturnToZero.setVisible(true);
                    jSpinnerStep.setVisible(true);
                    jButtonYPlus.setVisible(true);
                    jButtonYMinus.setVisible(true);
                    /* Elements de  GCode SENDER */
                    jButtonGCodePause.setVisible(true);
                    jLabelRowsInFile3.setVisible(true);
                    jLabelRowsInFile2.setVisible(true);
                    jLabelRowsInFile.setVisible(true);
                    jLabelSentRows.setVisible(true);
                    jButtonGCodeSend.setVisible(true);
                    jProgressBarGCodeProgress.setVisible(true);
                    jButtonGCodeCancel.setVisible(true);
                    jLabelRowsInFile1.setVisible(true);
                    jLabelRowsInFile4.setVisible(true);
                    jLabelTimeElapsed.setVisible(true);
                    jLabelRowsInFile5.setVisible(true);
                    jTextFieldGCodeFile.setVisible(true);
                    jLabel5.setVisible(true);
                    jLabelRemainingRows.setVisible(true);
                    /* Elements de TabbedPane */
                    /* TAB 1*/
                    jLabel7.setVisible(true);
                    jTextAreaConsole.setVisible(true);
                    jCheckBoxShowVerboseOutput.setVisible(true);
                    jTextFieldCommand.setVisible(true);
                    jButtonClearConsole.setVisible(true);
                    /* TAB 2*/
                    jTableGCodeLog.setVisible(true);
                    jButtonClearLog.setVisible(true);
                    jCheckBoxEnableGCodeLog.setVisible(true);
                    /* TAB 3*/
                    jLabel9.setVisible(true);
                    jLabel10.setVisible(true);
                    /* TAB 4*/
                    jLabel16.setVisible(true);
                    jLabelMachineHomePosition.setVisible(true);
                    jLabelLastStatusUpdate.setVisible(true);
                    jLabel17.setVisible(true);
                    level=3;
                    UpdateLevel();
                    tutorial=true;
                    
                    break;
                default:
                    tutorial=true;
                    break;
            }
            

        }
        
        
    }//GEN-LAST:event_formWindowOpened

    private void jButtonSoftReset3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonSoftReset3ActionPerformed
         try
        {
            WriteToConsole("Restarting...");
            jLabelActiveState2.setForeground(Color.MAGENTA);
            jLabelActiveState2.setText("Restarting...");
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendDataImmediately_WithoutMessageCollector(GRBLCommands.COMMAND_SOFT_RESET);
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.getMyGCodeSender().KillGCodeCycle();
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jButtonSoftReset3ActionPerformed
    private void jLabelActiveState2PropertyChange(java.beans.PropertyChangeEvent evt){
        
        
    }
    private void jButtonKillAlarm4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonKillAlarm4ActionPerformed
        try
        {
            // Send Kill Alarm lock command for both Kill Alarm and Machine Unlock
            ConnectionHelper.ACTIVE_CONNECTION_HANDLER.SendData(GRBLCommands.COMMAND_KILL_ALARM_LOCK);
        }
        catch (Exception ex)
        {
        }
    }//GEN-LAST:event_jButtonKillAlarm4ActionPerformed

    private void jButtonConnectDisconnect3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonConnectDisconnect3ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonConnectDisconnect3ActionPerformed

    private void jLabelActiveStateInputMethodTextChanged(java.awt.event.InputMethodEvent evt) {//GEN-FIRST:event_jLabelActiveStateInputMethodTextChanged
        
    }//GEN-LAST:event_jLabelActiveStateInputMethodTextChanged

    private void jLabelActiveStatePropertyChange(java.beans.PropertyChangeEvent evt) {//GEN-FIRST:event_jLabelActiveStatePropertyChange
        // TODO add your handling code here:
        if(lastState=="Homing..." && jLabelActiveState.getText()!="Homing..."){
            jButton25.setEnabled(true);
            jButton25.setSelected(true);
            jButton25.requestFocus();
            jButton14.setSelected(false);
        }
        jLabelActiveState2.setText(jLabelActiveState.getText());
        jLabelActiveState2.setForeground(jLabelActiveState.getForeground());
        lastState=jLabelActiveState2.getText();
        
            
            
        
    }//GEN-LAST:event_jLabelActiveStatePropertyChange

    private void jButton26ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton26ActionPerformed
        jDialog3.dispose();
        jDialogLevel.dispose();
        jDialogLevel.dispose();
    }//GEN-LAST:event_jButton26ActionPerformed

    private void formComponentResized(java.awt.event.ComponentEvent evt) {                                      

    }
    private void jCheckBoxEnableGCodeLog1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBoxEnableGCodeLog1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jCheckBoxEnableGCodeLog1ActionPerformed

    private void jButtonClearLog1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonClearLog1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonClearLog1ActionPerformed

    private void jButtonOk3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk3ActionPerformed
        jDialogTab2.dispose();
    }//GEN-LAST:event_jButtonOk3ActionPerformed

    private void jButtonCancel3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel3ActionPerformed
        tutorial=true;
        jDialogTab2.dispose();
    }//GEN-LAST:event_jButtonCancel3ActionPerformed

    private void jTableGCodeLogMouseEntered(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTableGCodeLogMouseEntered
        // TODO add your handling code here:
        jLabel49.setText("<html> Cette fenêtre permets de visualiser les commandes qui vont ou sont envoyées a la CNC. Il est possible de voir si la commande a été envoyées, éxécutéee ou une erreur est survenue. </html>");
    }//GEN-LAST:event_jTableGCodeLogMouseEntered

    private void jTableGCodeLogMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTableGCodeLogMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Cette fenêtre permets de visualiser les commandes qui vont ou sont envoyées a la CNC. Il est possible de voir si la commande a été envoyées, éxécutéee ou une erreur est survenue. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jTableGCodeLogMouseMoved

    private void jPanel2MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jPanel2MouseMoved
        // TODO add your handling code here:
         jLabel49.setText("<html> Cette fenêtre permets de voir la postition de la CNC. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jPanel2MouseMoved

    private void jPanelMachineControlMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jPanelMachineControlMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Cette fenêtre permets de controler le déplacement de la CNC. Il est possible de positionner la tête de découpe n'importe ou dans la zone de travail en sélectionnant le pas, et en cliquant sur la direction. N'utilisez pas une vitesse trop élevée si un matériel est placer dans la zone de travail. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jPanelMachineControlMouseMoved

    private void jPanelConnectionMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jPanelConnectionMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Cette interface permets de vérifier l'état de la connection avec la CNC.<br> Lorsqu'une erreur intervient, la CNC se mets en état \"Arlarm\". Pour continuer ou recommencer la découpe, cliquez sur \"Kill Alarm\", puis \"Soft Reset\".<br> En cas d'erreur de connection, cliquez sur \"Disconnect\" et changez le paramètres de connection dans System --> GRBL Settings</html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
        
    }//GEN-LAST:event_jPanelConnectionMouseMoved

    private void jPanel1MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jPanel1MouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce panneau permets d'importer et visualiser les détails de découpe du fichier. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
        
    }//GEN-LAST:event_jPanel1MouseMoved

    private void jTabbedPane1MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTabbedPane1MouseMoved
        // TODO add your handling code here:
        int tab=jTabbedPane1.getSelectedIndex();
        switch(tab){
            case 0:
                jLabel49.setText("<html> <h1> Console </h1> <p> Envoyez des commandes GCode dans la zone de texte, puis appuyez sur '↵' . </p> <br> La console peut être réinitialisée avec le bouton 'Clear Console'. Pour afficher les messages de la CNC, appuyez sur Show Verbose. </html>");
                jLabel49.invalidate();
                jLabel49.validate();
                jLabel49.repaint();
                break;
            case 1:
                jLabel49.setText("<html><h1>Historique GCode </h1> <p>Cette fenêtre permets de visualiser les commandes qui vont ou ont été envoyées a la CNC.<p><br> Il est possible de voir si la commande a été envoyées, éxécutéee ou une erreur est survenue. </html>");
                jLabel49.invalidate();
                jLabel49.validate();
                jLabel49.repaint();
                break;
            case 2:
                jLabel49.setText("<html> <h1> Raccourcis GCode </h1> <p>Ce panneau permets d'utiliser des commande prédéfinies rapidement.<P><br> Définissez l'action du boutton et il ne reste plus qu'a le cliquer. </html>");
                jLabel49.invalidate();
                jLabel49.validate();
                jLabel49.repaint();
                break;
            case 3:
                jLabel49.setText("<html><h1> Information Machine</h1> <p>Ce panneau affiche les informations de la CNC.</p> </html>");
                jLabel49.invalidate();
                jLabel49.validate();
                jLabel49.repaint();
                break;
            
                
        }       
    }//GEN-LAST:event_jTabbedPane1MouseMoved

    private void jButtonConnectDisconnect1MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonConnectDisconnect1MouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de déconnecter la CNC </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonConnectDisconnect1MouseMoved

    private void jButtonSoftResetMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonSoftResetMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de reinitialiser la CNC en cas d'ALARM. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonSoftResetMouseMoved

    private void jButtonKillAlarmMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonKillAlarmMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de supprimer une ALARM. Ensuite, appuyez sur soft reset. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonKillAlarmMouseMoved

    private void jButtonBrowseMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonBrowseMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de sélectionner le fichier GCode. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonBrowseMouseMoved

    private void jButtonVisualiseMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonVisualiseMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de visualiser la découpe qui seras effectuée. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonVisualiseMouseMoved

    private void jButtonGCodeCancelMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonGCodeCancelMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets d'annuler la découpe en cours. N'oubliez pas de déplacer la CNC avant de déplacer la matériel. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonGCodeCancelMouseMoved

    private void jButtonGCodePauseMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonGCodePauseMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de mettre la découpe en pause. Cliquez de nouveau pour relancer l'impression </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonGCodePauseMouseMoved

    private void jButtonClearConsoleMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonClearConsoleMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de supprimer les commandes de la console. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonClearConsoleMouseMoved

    private void jButton2MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButton2MouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de définir la position de l'axe X à Zéro. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButton2MouseMoved

    private void jButton3MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButton3MouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de définir la position de l'axe Y à Zéro. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButton3MouseMoved

    private void jButton1MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButton1MouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de définir la position de l'axe Z à Zéro. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButton1MouseMoved

    private void jButtonResetWorkPositionMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonResetWorkPositionMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de définir la position des axes X,Y et Z à Zéro. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonResetWorkPositionMouseMoved

    private void jButtonGCodeSendMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonGCodeSendMouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets d'envoyer le GCode à la CNC. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jButtonGCodeSendMouseMoved

    private void jMenuItem5MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jMenuItem5MouseMoved
        // TODO add your handling code here:
        jLabel49.setText("<html> Ce boutton permets de définir la position de l'axe X à Zéro. </html>");
        jLabel49.invalidate();
        jLabel49.validate();
        jLabel49.repaint();
    }//GEN-LAST:event_jMenuItem5MouseMoved

    private void jButton15ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton15ActionPerformed
        // TODO add your handling code here:
        jDialogAskTuto.setVisible(false);
    }//GEN-LAST:event_jButton15ActionPerformed

    private void jButton16ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton16ActionPerformed
        // TODO add your handling code here:
        tutorial=false;
        jDialogAskTuto.setVisible(false);
    }//GEN-LAST:event_jButton16ActionPerformed

    private void jButtonCancel6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel6ActionPerformed
        // TODO add your handling code here:
        jDialogMachineAxis.dispose();
        tutorial=true;
    }//GEN-LAST:event_jButtonCancel6ActionPerformed

    private void jButtonOk6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk6ActionPerformed
        // TODO add your handling code here:
        jDialogMachineAxis.dispose();
    }//GEN-LAST:event_jButtonOk6ActionPerformed

    private void jRadioButtonInches2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRadioButtonInches2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jRadioButtonInches2ActionPerformed

    private void jRadioButtonMillimeters2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRadioButtonMillimeters2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jRadioButtonMillimeters2ActionPerformed

    private void jButtonYMinus2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonYMinus2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonYMinus2ActionPerformed

    private void jButtonXMinus2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonXMinus2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonXMinus2ActionPerformed

    private void jButtonYPlus2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonYPlus2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonYPlus2ActionPerformed

    private void jButtonXPlus2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonXPlus2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonXPlus2ActionPerformed

    private void jButtonZPlus2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonZPlus2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonZPlus2ActionPerformed

    private void jButtonZMinus2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonZMinus2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonZMinus2ActionPerformed

    private void jCheckBoxEnableKeyboardJogging2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBoxEnableKeyboardJogging2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jCheckBoxEnableKeyboardJogging2ActionPerformed

    private void jLabelRemoveFocus2MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelRemoveFocus2MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelRemoveFocus2MouseClicked

    private void jButtonReturnToZero2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonReturnToZero2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonReturnToZero2ActionPerformed

    private void jSliderStepSize2StateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_jSliderStepSize2StateChanged
        // TODO add your handling code here:
    }//GEN-LAST:event_jSliderStepSize2StateChanged

    private void jPanelMachineControl2MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jPanelMachineControl2MouseMoved
        // TODO add your handling code here:
    }//GEN-LAST:event_jPanelMachineControl2MouseMoved

    private void jButtonBrowse2MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonBrowse2MouseMoved
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonBrowse2MouseMoved

    private void jButtonBrowse2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonBrowse2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonBrowse2ActionPerformed

    private void jButtonVisualise2MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jButtonVisualise2MouseMoved
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonVisualise2MouseMoved

    private void jButtonVisualise2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonVisualise2ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonVisualise2ActionPerformed

    private void jTableGCodeLog2MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTableGCodeLog2MouseMoved
        // TODO add your handling code here:
    }//GEN-LAST:event_jTableGCodeLog2MouseMoved

    private void jTableGCodeLog2MouseEntered(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTableGCodeLog2MouseEntered
        // TODO add your handling code here:
    }//GEN-LAST:event_jTableGCodeLog2MouseEntered

    private void jButtonOk7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk7ActionPerformed
        // TODO add your handling code here:
        JDialogSecuriter.dispose();
    }//GEN-LAST:event_jButtonOk7ActionPerformed

    private void jButtonCancel7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel7ActionPerformed
        // TODO add your handling code here:
        JDialogSecuriter.dispose();
        
    }//GEN-LAST:event_jButtonCancel7ActionPerformed

    private void jTableGCodeLog3MouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTableGCodeLog3MouseMoved
        // TODO add your handling code here:
    }//GEN-LAST:event_jTableGCodeLog3MouseMoved

    private void jTableGCodeLog3MouseEntered(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jTableGCodeLog3MouseEntered
        // TODO add your handling code here:
    }//GEN-LAST:event_jTableGCodeLog3MouseEntered

    private void jButtonOk8ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk8ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonOk8ActionPerformed

    private void jButtonCancel8ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel8ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonCancel8ActionPerformed

    private void JDialogLimitationsMouseDragged(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_JDialogLimitationsMouseDragged
        // TODO add your handling code here:
    }//GEN-LAST:event_JDialogLimitationsMouseDragged
    private void formMouseDragged(java.awt.event.MouseEvent evt){
        
        
    }


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JDialog JDialogLimitations;
    private javax.swing.JDialog JDialogSecuriter;
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButton10;
    private javax.swing.JButton jButton11;
    private javax.swing.JButton jButton12;
    private javax.swing.JButton jButton13;
    private javax.swing.JButton jButton14;
    private javax.swing.JButton jButton15;
    private javax.swing.JButton jButton16;
    private javax.swing.JButton jButton2;
    private javax.swing.JButton jButton24;
    private javax.swing.JButton jButton25;
    private javax.swing.JButton jButton26;
    private javax.swing.JButton jButton27;
    private javax.swing.JButton jButton3;
    private javax.swing.JButton jButton4;
    private javax.swing.JButton jButton5;
    private javax.swing.JButton jButton6;
    private javax.swing.JButton jButton7;
    private javax.swing.JButton jButton8;
    private javax.swing.JButton jButton9;
    private javax.swing.JButton jButtonBrowse;
    private javax.swing.JButton jButtonBrowse1;
    private javax.swing.JButton jButtonBrowse2;
    private javax.swing.JButton jButtonCancel;
    private javax.swing.JButton jButtonCancel1;
    private javax.swing.JButton jButtonCancel2;
    private javax.swing.JButton jButtonCancel3;
    private javax.swing.JButton jButtonCancel4;
    private javax.swing.JButton jButtonCancel5;
    private javax.swing.JButton jButtonCancel6;
    private javax.swing.JButton jButtonCancel7;
    private javax.swing.JButton jButtonCancel8;
    private javax.swing.JButton jButtonClearConsole;
    private javax.swing.JButton jButtonClearConsole1;
    private javax.swing.JButton jButtonClearLog;
    private javax.swing.JButton jButtonClearLog1;
    private javax.swing.JButton jButtonConnectDisconnect1;
    private javax.swing.JButton jButtonConnectDisconnect2;
    private javax.swing.JButton jButtonConnectDisconnect3;
    private javax.swing.JButton jButtonConnectDisconnect7;
    private javax.swing.JButton jButtonGCodeBrowse;
    private javax.swing.JButton jButtonGCodeBrowse1;
    private javax.swing.JButton jButtonGCodeBrowse2;
    private javax.swing.JButton jButtonGCodeCancel;
    private javax.swing.JButton jButtonGCodeCancel1;
    private javax.swing.JButton jButtonGCodeCancel2;
    private javax.swing.JButton jButtonGCodePause;
    private javax.swing.JButton jButtonGCodePause1;
    private javax.swing.JButton jButtonGCodePause2;
    private javax.swing.JButton jButtonGCodeSend;
    private javax.swing.JButton jButtonGCodeSend1;
    private javax.swing.JButton jButtonGCodeSend2;
    private javax.swing.JButton jButtonGCodeVisualize;
    private javax.swing.JButton jButtonGCodeVisualize1;
    private javax.swing.JButton jButtonGCodeVisualize2;
    private javax.swing.JButton jButtonKillAlarm;
    private javax.swing.JButton jButtonKillAlarm1;
    private javax.swing.JButton jButtonKillAlarm2;
    private javax.swing.JButton jButtonKillAlarm3;
    private javax.swing.JButton jButtonKillAlarm4;
    private javax.swing.JButton jButtonOk;
    private javax.swing.JButton jButtonOk1;
    private javax.swing.JButton jButtonOk2;
    private javax.swing.JButton jButtonOk3;
    private javax.swing.JButton jButtonOk4;
    private javax.swing.JButton jButtonOk5;
    private javax.swing.JButton jButtonOk6;
    private javax.swing.JButton jButtonOk7;
    private javax.swing.JButton jButtonOk8;
    private javax.swing.JButton jButtonResetWorkPosition;
    private javax.swing.JButton jButtonResetWorkPosition1;
    private javax.swing.JButton jButtonReturnToZero;
    private javax.swing.JButton jButtonReturnToZero1;
    private javax.swing.JButton jButtonReturnToZero2;
    private javax.swing.JButton jButtonSoftReset;
    private javax.swing.JButton jButtonSoftReset1;
    private javax.swing.JButton jButtonSoftReset2;
    private javax.swing.JButton jButtonSoftReset3;
    private javax.swing.JButton jButtonVisualise;
    private javax.swing.JButton jButtonVisualise1;
    private javax.swing.JButton jButtonVisualise2;
    private javax.swing.JButton jButtonXMinus;
    private javax.swing.JButton jButtonXMinus1;
    private javax.swing.JButton jButtonXMinus2;
    private javax.swing.JButton jButtonXPlus;
    private javax.swing.JButton jButtonXPlus1;
    private javax.swing.JButton jButtonXPlus2;
    private javax.swing.JButton jButtonYMinus;
    private javax.swing.JButton jButtonYMinus1;
    private javax.swing.JButton jButtonYMinus2;
    private javax.swing.JButton jButtonYPlus;
    private javax.swing.JButton jButtonYPlus1;
    private javax.swing.JButton jButtonYPlus2;
    private javax.swing.JButton jButtonZMinus;
    private javax.swing.JButton jButtonZMinus1;
    private javax.swing.JButton jButtonZMinus2;
    private javax.swing.JButton jButtonZPlus;
    private javax.swing.JButton jButtonZPlus1;
    private javax.swing.JButton jButtonZPlus2;
    private javax.swing.JCheckBox jCheckBoxEnableGCodeLog;
    private javax.swing.JCheckBox jCheckBoxEnableGCodeLog1;
    private javax.swing.JCheckBox jCheckBoxEnableKeyboardJogging;
    private javax.swing.JCheckBox jCheckBoxEnableKeyboardJogging1;
    private javax.swing.JCheckBox jCheckBoxEnableKeyboardJogging2;
    private javax.swing.JCheckBox jCheckBoxShowVerboseOutput;
    private javax.swing.JCheckBox jCheckBoxShowVerboseOutput1;
    private javax.swing.JComboBox<String> jComboBox1;
    private javax.swing.JComboBox<String> jComboBox2;
    private javax.swing.JButton jConfirmerBoutton;
    private javax.swing.JDialog jDialog1;
    private javax.swing.JDialog jDialog2;
    private javax.swing.JDialog jDialog3;
    private javax.swing.JDialog jDialogAskTuto;
    private javax.swing.JDialog jDialogGSender;
    private javax.swing.JDialog jDialogImportGCode;
    private javax.swing.JDialog jDialogLevel;
    private javax.swing.JDialog jDialogMachineAxis;
    private javax.swing.JDialog jDialogMiseEnPlace;
    private javax.swing.JDialog jDialogTab1;
    private javax.swing.JDialog jDialogTab2;
    private javax.swing.JDialog jDialogTab3;
    private javax.swing.JDialog jDialogTab4;
    private javax.swing.JDialog jDialogZeroAxes;
    private javax.swing.JFrame jFrame1;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel20;
    private javax.swing.JLabel jLabel21;
    private javax.swing.JLabel jLabel22;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel24;
    private javax.swing.JLabel jLabel25;
    private javax.swing.JLabel jLabel26;
    private javax.swing.JLabel jLabel27;
    private javax.swing.JLabel jLabel28;
    private javax.swing.JLabel jLabel29;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel30;
    private javax.swing.JLabel jLabel31;
    private javax.swing.JLabel jLabel32;
    private javax.swing.JLabel jLabel33;
    private javax.swing.JLabel jLabel34;
    private javax.swing.JLabel jLabel35;
    private javax.swing.JLabel jLabel36;
    private javax.swing.JLabel jLabel37;
    private javax.swing.JLabel jLabel38;
    private javax.swing.JLabel jLabel39;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel40;
    private javax.swing.JLabel jLabel41;
    private javax.swing.JLabel jLabel42;
    private javax.swing.JLabel jLabel43;
    private javax.swing.JLabel jLabel44;
    private javax.swing.JLabel jLabel45;
    private javax.swing.JLabel jLabel46;
    private javax.swing.JLabel jLabel47;
    private javax.swing.JLabel jLabel48;
    private javax.swing.JLabel jLabel49;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel50;
    private javax.swing.JLabel jLabel51;
    private javax.swing.JLabel jLabel52;
    private javax.swing.JLabel jLabel53;
    private javax.swing.JLabel jLabel54;
    private javax.swing.JLabel jLabel55;
    private javax.swing.JLabel jLabel56;
    private javax.swing.JLabel jLabel57;
    private javax.swing.JLabel jLabel58;
    private javax.swing.JLabel jLabel59;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel60;
    private javax.swing.JLabel jLabel65;
    private javax.swing.JLabel jLabel66;
    private javax.swing.JLabel jLabel67;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel77;
    private javax.swing.JLabel jLabel78;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel88;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JLabel jLabelActiveState;
    private javax.swing.JLabel jLabelActiveState1;
    private javax.swing.JLabel jLabelActiveState2;
    private javax.swing.JLabel jLabelLastStatusUpdate;
    private javax.swing.JLabel jLabelMachineHomePosition;
    private javax.swing.JLabel jLabelMachinePositionX;
    private javax.swing.JLabel jLabelMachinePositionX1;
    private javax.swing.JLabel jLabelMachinePositionY;
    private javax.swing.JLabel jLabelMachinePositionY1;
    private javax.swing.JLabel jLabelMachinePositionZ;
    private javax.swing.JLabel jLabelMachinePositionZ1;
    private javax.swing.JLabel jLabelMachineX1;
    private javax.swing.JLabel jLabelMachineX2;
    private javax.swing.JLabel jLabelMachineX3;
    private javax.swing.JLabel jLabelRealTimeFeedRate;
    private javax.swing.JLabel jLabelRealTimeFeedRate1;
    private javax.swing.JLabel jLabelRealTimeFeedRate2;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM1;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM2;
    private javax.swing.JLabel jLabelRecommendedRPM;
    private javax.swing.JLabel jLabelRecommendedRPM1;
    private javax.swing.JLabel jLabelRemainingRows;
    private javax.swing.JLabel jLabelRemainingRows1;
    private javax.swing.JLabel jLabelRemainingRows2;
    private javax.swing.JLabel jLabelRemoveFocus;
    private javax.swing.JLabel jLabelRemoveFocus1;
    private javax.swing.JLabel jLabelRemoveFocus2;
    private javax.swing.JLabel jLabelRowsInFile;
    private javax.swing.JLabel jLabelRowsInFile1;
    private javax.swing.JLabel jLabelRowsInFile10;
    private javax.swing.JLabel jLabelRowsInFile14;
    private javax.swing.JLabel jLabelRowsInFile15;
    private javax.swing.JLabel jLabelRowsInFile16;
    private javax.swing.JLabel jLabelRowsInFile17;
    private javax.swing.JLabel jLabelRowsInFile18;
    private javax.swing.JLabel jLabelRowsInFile19;
    private javax.swing.JLabel jLabelRowsInFile2;
    private javax.swing.JLabel jLabelRowsInFile20;
    private javax.swing.JLabel jLabelRowsInFile21;
    private javax.swing.JLabel jLabelRowsInFile3;
    private javax.swing.JLabel jLabelRowsInFile4;
    private javax.swing.JLabel jLabelRowsInFile5;
    private javax.swing.JLabel jLabelRowsInFile6;
    private javax.swing.JLabel jLabelRowsInFile7;
    private javax.swing.JLabel jLabelRowsInFile8;
    private javax.swing.JLabel jLabelRowsInFile9;
    private javax.swing.JLabel jLabelSemiAutoToolChangeStatus;
    private javax.swing.JLabel jLabelSemiAutoToolChangeStatus1;
    private javax.swing.JLabel jLabelSentRows;
    private javax.swing.JLabel jLabelSentRows1;
    private javax.swing.JLabel jLabelSentRows2;
    private javax.swing.JLabel jLabelTimeElapsed;
    private javax.swing.JLabel jLabelTimeElapsed1;
    private javax.swing.JLabel jLabelWorkPositionX;
    private javax.swing.JLabel jLabelWorkPositionX1;
    private javax.swing.JLabel jLabelWorkPositionY;
    private javax.swing.JLabel jLabelWorkPositionY1;
    private javax.swing.JLabel jLabelWorkPositionZ;
    private javax.swing.JLabel jLabelWorkPositionZ1;
    private javax.swing.JLayeredPane jLayeredPane1;
    private javax.swing.JComboBox<String> jLevelCombo;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JMenu jMenu2;
    private javax.swing.JMenu jMenu3;
    private javax.swing.JMenu jMenu4;
    private javax.swing.JMenu jMenu5;
    private javax.swing.JMenu jMenu6;
    private javax.swing.JMenuBar jMenuBar1;
    private javax.swing.JMenuItem jMenuItem1;
    private javax.swing.JMenuItem jMenuItem10;
    private javax.swing.JMenuItem jMenuItem11;
    private javax.swing.JMenuItem jMenuItem12;
    private javax.swing.JMenuItem jMenuItem2;
    private javax.swing.JMenuItem jMenuItem3;
    private javax.swing.JMenuItem jMenuItem4;
    private javax.swing.JMenuItem jMenuItem5;
    private javax.swing.JMenuItem jMenuItem6;
    private javax.swing.JMenuItem jMenuItem7;
    private javax.swing.JMenuItem jMenuItem8;
    private javax.swing.JMenuItem jMenuItem9;
    private javax.swing.JMenuItem jMenuItemExit;
    private javax.swing.JMenuItem jMenuItemGRBLSettings;
    private javax.swing.JMenuItem jMenuItemHoleCenterFinder;
    private javax.swing.JMenuItem jMenuItemStartHomingSequence;
    private javax.swing.JMenuItem jMenuItemToolChangeSettings;
    private javax.swing.JMenuItem jMenuSetWorkPos;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel10;
    private javax.swing.JPanel jPanel11;
    private javax.swing.JPanel jPanel12;
    private javax.swing.JPanel jPanel13;
    private javax.swing.JPanel jPanel14;
    private javax.swing.JPanel jPanel15;
    private javax.swing.JPanel jPanel16;
    private javax.swing.JPanel jPanel17;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel20;
    private javax.swing.JPanel jPanel24;
    private javax.swing.JPanel jPanel25;
    private javax.swing.JPanel jPanel26;
    private javax.swing.JPanel jPanel28;
    private javax.swing.JPanel jPanel29;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel30;
    private javax.swing.JPanel jPanel31;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JPanel jPanel8;
    private javax.swing.JPanel jPanel9;
    private javax.swing.JPanel jPanelConnection;
    private javax.swing.JPanel jPanelConnection1;
    private javax.swing.JPanel jPanelConnection2;
    private javax.swing.JPanel jPanelConnection4;
    private javax.swing.JPanel jPanelGCodeFile;
    private javax.swing.JPanel jPanelGCodeFile1;
    private javax.swing.JPanel jPanelGCodeFile2;
    private javax.swing.JPanel jPanelJogButtons;
    private javax.swing.JPanel jPanelJogButtons1;
    private javax.swing.JPanel jPanelJogButtons2;
    private javax.swing.JPanel jPanelMachineControl;
    private javax.swing.JPanel jPanelMachineControl1;
    private javax.swing.JPanel jPanelMachineControl2;
    private javax.swing.JPanel jPanelMacros;
    private javax.swing.JProgressBar jProgressBarGCodeProgress;
    private javax.swing.JProgressBar jProgressBarGCodeProgress1;
    private javax.swing.JProgressBar jProgressBarGCodeProgress2;
    private javax.swing.JRadioButton jRadioButtonInches;
    private javax.swing.JRadioButton jRadioButtonInches1;
    private javax.swing.JRadioButton jRadioButtonInches2;
    private javax.swing.JRadioButton jRadioButtonMillimeters;
    private javax.swing.JRadioButton jRadioButtonMillimeters1;
    private javax.swing.JRadioButton jRadioButtonMillimeters2;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JScrollPane jScrollPane6;
    private javax.swing.JScrollPane jScrollPane7;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JSeparator jSeparator10;
    private javax.swing.JSeparator jSeparator11;
    private javax.swing.JSeparator jSeparator12;
    private javax.swing.JSeparator jSeparator13;
    private javax.swing.JSeparator jSeparator14;
    private javax.swing.JSeparator jSeparator15;
    private javax.swing.JSeparator jSeparator16;
    private javax.swing.JSeparator jSeparator17;
    private javax.swing.JSeparator jSeparator18;
    private javax.swing.JPopupMenu.Separator jSeparator19;
    private javax.swing.JSeparator jSeparator2;
    private javax.swing.JSeparator jSeparator20;
    private javax.swing.JSeparator jSeparator21;
    private javax.swing.JPopupMenu.Separator jSeparator22;
    private javax.swing.JSeparator jSeparator3;
    private javax.swing.JSeparator jSeparator4;
    private javax.swing.JSeparator jSeparator5;
    private javax.swing.JSeparator jSeparator6;
    private javax.swing.JSeparator jSeparator7;
    private javax.swing.JSeparator jSeparator8;
    private javax.swing.JSeparator jSeparator9;
    private javax.swing.JSlider jSliderStepSize;
    private javax.swing.JSlider jSliderStepSize1;
    private javax.swing.JSlider jSliderStepSize2;
    private javax.swing.JSpinner jSpinnerStep;
    private javax.swing.JSpinner jSpinnerStep1;
    private javax.swing.JSpinner jSpinnerStep2;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JTable jTable1;
    private javax.swing.JTable jTableGCodeLog;
    private javax.swing.JTable jTableGCodeLog1;
    private javax.swing.JTable jTableGCodeLog2;
    private javax.swing.JTable jTableGCodeLog3;
    private javax.swing.JTextArea jTextAreaConsole;
    private javax.swing.JTextArea jTextAreaConsole1;
    private javax.swing.JTextField jTextFieldCommand;
    private javax.swing.JTextField jTextFieldCommand1;
    private javax.swing.JTextField jTextFieldGCodeFile;
    private javax.swing.JTextField jTextFieldGCodeFile1;
    private javax.swing.JTextField jTextFieldGCodeFile2;
    // End of variables declaration//GEN-END:variables
    boolean tutorial=true;
    int level=1;
    int indexonglets;
    int process_fini=0;
    private String info;
    String lastState="";
   
}
