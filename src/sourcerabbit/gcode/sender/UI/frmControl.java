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

import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.io.File;
import java.text.DateFormat;
import java.text.DecimalFormat;
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
import javax.swing.JToolTip;
import javax.swing.MenuElement;
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

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jDialogLevel = new javax.swing.JDialog();
        jPanel13 = new javax.swing.JPanel();
        jConfirmerBoutton = new javax.swing.JButton();
        jLevelCombo = new javax.swing.JComboBox<>();
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
        jSeparator5 = new javax.swing.JSeparator();
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
        jPanel16 = new javax.swing.JPanel();
        jButtonResetWorkPosition4 = new javax.swing.JButton();
        jPanel17 = new javax.swing.JPanel();
        jLabel57 = new javax.swing.JLabel();
        jLabelWorkPositionZ4 = new javax.swing.JLabel();
        jLabelWorkPositionX4 = new javax.swing.JLabel();
        jLabelWorkPositionY4 = new javax.swing.JLabel();
        jLabelMachinePositionZ4 = new javax.swing.JLabel();
        jLabelMachinePositionX4 = new javax.swing.JLabel();
        jLabelMachinePositionY4 = new javax.swing.JLabel();
        jLabel58 = new javax.swing.JLabel();
        jLabel59 = new javax.swing.JLabel();
        jLabel60 = new javax.swing.JLabel();
        jButton15 = new javax.swing.JButton();
        jButton16 = new javax.swing.JButton();
        jButton17 = new javax.swing.JButton();
        jLabelRowsInFile11 = new javax.swing.JLabel();
        jLabelSemiAutoToolChangeStatus4 = new javax.swing.JLabel();
        jLabel61 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate7 = new javax.swing.JLabel();
        jLabel62 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM7 = new javax.swing.JLabel();
        jLabel63 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate8 = new javax.swing.JLabel();
        jLabel64 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM8 = new javax.swing.JLabel();
        jLabel65 = new javax.swing.JLabel();
        jButtonCancel3 = new javax.swing.JButton();
        jButtonOk3 = new javax.swing.JButton();
        jDialogTab3 = new javax.swing.JDialog();
        jLabel66 = new javax.swing.JLabel();
        jLabel67 = new javax.swing.JLabel();
        jSeparator14 = new javax.swing.JSeparator();
        jSeparator15 = new javax.swing.JSeparator();
        jPanel18 = new javax.swing.JPanel();
        jButtonResetWorkPosition5 = new javax.swing.JButton();
        jPanel19 = new javax.swing.JPanel();
        jLabel68 = new javax.swing.JLabel();
        jLabelWorkPositionZ5 = new javax.swing.JLabel();
        jLabelWorkPositionX5 = new javax.swing.JLabel();
        jLabelWorkPositionY5 = new javax.swing.JLabel();
        jLabelMachinePositionZ5 = new javax.swing.JLabel();
        jLabelMachinePositionX5 = new javax.swing.JLabel();
        jLabelMachinePositionY5 = new javax.swing.JLabel();
        jLabel69 = new javax.swing.JLabel();
        jLabel70 = new javax.swing.JLabel();
        jLabel71 = new javax.swing.JLabel();
        jButton18 = new javax.swing.JButton();
        jButton19 = new javax.swing.JButton();
        jButton20 = new javax.swing.JButton();
        jLabelRowsInFile12 = new javax.swing.JLabel();
        jLabelSemiAutoToolChangeStatus5 = new javax.swing.JLabel();
        jLabel72 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate9 = new javax.swing.JLabel();
        jLabel73 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM9 = new javax.swing.JLabel();
        jLabel74 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate10 = new javax.swing.JLabel();
        jLabel75 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM10 = new javax.swing.JLabel();
        jLabel76 = new javax.swing.JLabel();
        jButtonCancel4 = new javax.swing.JButton();
        jButtonOk4 = new javax.swing.JButton();
        jDialogTab4 = new javax.swing.JDialog();
        jLabel77 = new javax.swing.JLabel();
        jLabel78 = new javax.swing.JLabel();
        jSeparator16 = new javax.swing.JSeparator();
        jSeparator17 = new javax.swing.JSeparator();
        jPanel20 = new javax.swing.JPanel();
        jButtonResetWorkPosition6 = new javax.swing.JButton();
        jPanel21 = new javax.swing.JPanel();
        jLabel79 = new javax.swing.JLabel();
        jLabelWorkPositionZ6 = new javax.swing.JLabel();
        jLabelWorkPositionX6 = new javax.swing.JLabel();
        jLabelWorkPositionY6 = new javax.swing.JLabel();
        jLabelMachinePositionZ6 = new javax.swing.JLabel();
        jLabelMachinePositionX6 = new javax.swing.JLabel();
        jLabelMachinePositionY6 = new javax.swing.JLabel();
        jLabel80 = new javax.swing.JLabel();
        jLabel81 = new javax.swing.JLabel();
        jLabel82 = new javax.swing.JLabel();
        jButton21 = new javax.swing.JButton();
        jButton22 = new javax.swing.JButton();
        jButton23 = new javax.swing.JButton();
        jLabelRowsInFile13 = new javax.swing.JLabel();
        jLabelSemiAutoToolChangeStatus6 = new javax.swing.JLabel();
        jLabel83 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate11 = new javax.swing.JLabel();
        jLabel84 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM11 = new javax.swing.JLabel();
        jLabel85 = new javax.swing.JLabel();
        jLabelRealTimeFeedRate12 = new javax.swing.JLabel();
        jLabel86 = new javax.swing.JLabel();
        jLabelRealTimeSpindleRPM12 = new javax.swing.JLabel();
        jLabel87 = new javax.swing.JLabel();
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
        jPanel22 = new javax.swing.JPanel();
        jPanel23 = new javax.swing.JPanel();
        jLabel38 = new javax.swing.JLabel();
        jButton12 = new javax.swing.JButton();
        jButton13 = new javax.swing.JButton();
        jDialogZeroAxes = new javax.swing.JDialog();
        jPanel24 = new javax.swing.JPanel();
        jPanel25 = new javax.swing.JPanel();
        jPanel27 = new javax.swing.JPanel();
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
        jLabel1 = new javax.swing.JLabel();
        jScrollPane3 = new javax.swing.JScrollPane();
        jTextPane1 = new javax.swing.JTextPane();
        jComboBox1 = new javax.swing.JComboBox<>();
        jComboBox2 = new javax.swing.JComboBox<>();
        jScrollPane4 = new javax.swing.JScrollPane();
        jTextPane2 = new javax.swing.JTextPane();
        jLabel11 = new javax.swing.JLabel();
        jLabel13 = new javax.swing.JLabel();
        jSeparator3 = new javax.swing.JSeparator();
        jScrollPane5 = new javax.swing.JScrollPane();
        jTextPane3 = new javax.swing.JTextPane();
        jLabelRecommendedRPM = new javax.swing.JLabel();
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
        jPanelConnection = new javax.swing.JPanel();
        jButtonSoftReset = new javax.swing.JButton();
        jLabelMachineX1 = new javax.swing.JLabel();
        jButtonKillAlarm = new javax.swing.JButton();
        jLabelActiveState = new javax.swing.JLabel();
        jButtonConnectDisconnect1 = new javax.swing.JButton();
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
        jMenuItem10 = new javax.swing.JMenuItem();
        jMenuItem1 = new javax.swing.JMenuItem();
        jMenu3 = new javax.swing.JMenu();
        jMenuItem3 = new javax.swing.JMenuItem();
        jMenuItem4 = new javax.swing.JMenuItem();
        jMenu6 = new javax.swing.JMenu();
        jMenuItem6 = new javax.swing.JMenuItem();
        jMenuItem7 = new javax.swing.JMenuItem();
        jMenuItem8 = new javax.swing.JMenuItem();

        jDialogLevel.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        jDialogLevel.setModalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL);

        jConfirmerBoutton.setText("Confirmer");
        jConfirmerBoutton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jConfirmerBouttonActionPerformed(evt);
            }
        });

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
                .addGroup(jPanel13Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel13Layout.createSequentialGroup()
                        .addGap(103, 103, 103)
                        .addComponent(jConfirmerBoutton))
                    .addGroup(jPanel13Layout.createSequentialGroup()
                        .addGap(66, 66, 66)
                        .addComponent(jLevelCombo, javax.swing.GroupLayout.PREFERRED_SIZE, 151, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(68, Short.MAX_VALUE))
        );
        jPanel13Layout.setVerticalGroup(
            jPanel13Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel13Layout.createSequentialGroup()
                .addGap(43, 43, 43)
                .addComponent(jLevelCombo, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 63, Short.MAX_VALUE)
                .addComponent(jConfirmerBoutton)
                .addContainerGap())
        );

        javax.swing.GroupLayout jDialogLevelLayout = new javax.swing.GroupLayout(jDialogLevel.getContentPane());
        jDialogLevel.getContentPane().setLayout(jDialogLevelLayout);
        jDialogLevelLayout.setHorizontalGroup(
            jDialogLevelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogLevelLayout.createSequentialGroup()
                .addGap(35, 35, 35)
                .addComponent(jPanel13, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(43, Short.MAX_VALUE))
        );
        jDialogLevelLayout.setVerticalGroup(
            jDialogLevelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogLevelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel13, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(28, Short.MAX_VALUE))
        );

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
        jLabelActiveState1.setText("Restarting...");
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
            .addGroup(jPanel8Layout.createSequentialGroup()
                .addContainerGap()
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
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
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
                    .addComponent(jSeparator5)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel8, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel18)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jDialog1Layout.createSequentialGroup()
                                .addGap(0, 0, Short.MAX_VALUE)
                                .addComponent(jPanelConnection1, javax.swing.GroupLayout.PREFERRED_SIZE, 292, javax.swing.GroupLayout.PREFERRED_SIZE))))
                    .addComponent(jPanel8, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
            .addGroup(jDialog1Layout.createSequentialGroup()
                .addGap(99, 99, 99)
                .addComponent(jButton7, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButton8, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(99, 99, 99))
        );
        jDialog1Layout.setVerticalGroup(
            jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator4, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanelConnection1, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(9, 9, 9)
                .addComponent(jSeparator5, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel18, javax.swing.GroupLayout.PREFERRED_SIZE, 67, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jPanel8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton7)
                    .addComponent(jButton8))
                .addContainerGap())
        );

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

        jLabel24.setText("<html>\nLa vitesse de découpe et de rotation doivent être ajustées en fonction du matériel de découpe et du fôrêt utilisé.<br>\n</html>\n");

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
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator7)
                    .addGroup(jDialog2Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel24, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                            .addComponent(jLabel22, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel23))))
                .addContainerGap())
            .addGroup(jDialog2Layout.createSequentialGroup()
                .addGroup(jDialog2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
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
                        .addGap(56, 56, 56)
                        .addComponent(jPanel10, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(62, 62, 62))
            .addGroup(jDialog2Layout.createSequentialGroup()
                .addGap(102, 102, 102)
                .addComponent(jButtonOk, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(102, 102, 102))
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
                .addComponent(jLabel23, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(30, 30, 30)
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
                .addContainerGap())
        );

        jDialogGSender.setModal(true);
        jDialogGSender.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel25.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel25.setText("<html> <h3 text-decoration=\"bold\"> Interface de sélection G Code");
        jLabel25.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel26.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel26.setText("<html> <h2><center>Importer l'adresse du fichier a découper </center> </h2>   Il est possible de mettre en pause ou annuler la découpe ici cependant l'installation d'un bouton d'URGENCE est recommandé !<br><br> L'avancée de l'impression s'affiche sur la barre de progrès </html> ");
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
                .addGap(582, 582, 582)
                .addComponent(jButtonGCodeVisualize1)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButtonGCodeBrowse1, javax.swing.GroupLayout.PREFERRED_SIZE, 76, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );
        jPanelGCodeFile1Layout.setVerticalGroup(
            jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFile1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel88)
                    .addComponent(jTextFieldGCodeFile1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanelGCodeFile1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonGCodePause1)
                    .addComponent(jButtonGCodeSend1)
                    .addComponent(jButtonGCodeCancel1)
                    .addComponent(jButtonGCodeVisualize1)
                    .addComponent(jButtonGCodeBrowse1))
                .addContainerGap(15, Short.MAX_VALUE))
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
                            .addComponent(jPanel9, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addGroup(jDialogGSenderLayout.createSequentialGroup()
                        .addGap(179, 179, 179)
                        .addComponent(jButtonOk1, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(32, 32, 32)
                        .addComponent(jButtonCancel1, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(jLabel26, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE))
                .addContainerGap())
            .addGroup(jDialogGSenderLayout.createSequentialGroup()
                .addGap(103, 103, 103)
                .addComponent(jLabelRowsInFile19, javax.swing.GroupLayout.PREFERRED_SIZE, 66, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(14, 14, 14)
                .addComponent(jProgressBarGCodeProgress2, javax.swing.GroupLayout.PREFERRED_SIZE, 230, javax.swing.GroupLayout.PREFERRED_SIZE)
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
                .addGap(18, 18, 18)
                .addComponent(jLabel26, javax.swing.GroupLayout.PREFERRED_SIZE, 129, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(28, 28, 28)
                .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabelRowsInFile19)
                    .addComponent(jProgressBarGCodeProgress2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(56, 56, 56)
                .addGroup(jDialogGSenderLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk1)
                    .addComponent(jButtonCancel1))
                .addContainerGap())
        );

        jDialogTab1.setModal(true);
        jDialogTab1.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel44.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel44.setText("<html> <h3 text-decoration=\"bold\"> Interface d'envoie GCODE");
        jLabel44.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel45.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel45.setText("<html> <h4><center>Cette interface envoie des commandes GCODE</center></h4>  \nIl est possible de controler les paramètres de la CNC, sa configuration et ses déplacements via des commandes G Code.<br>\n<br>\nUn tutoriel plus complet est accessible dans le menu <i> Aide</i> puis <i> Tutoriel G-Code </i>");
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
            .addGroup(jDialogTab1Layout.createSequentialGroup()
                .addGroup(jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator11)
                    .addGroup(jDialogTab1Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogTab1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel44, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel45, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE))))
                .addContainerGap())
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
                .addContainerGap(83, Short.MAX_VALUE))
            .addComponent(jSeparator10)
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
        jLabel55.setText("<html> <h3 text-decoration=\"bold\"> Interface de Connection");
        jLabel55.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel56.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel56.setText("<html> <h4 text-align=\"center\">Cette interface permets de voir laposition de la CNC </h4>  \nLa position des axes XYZ est affichée. ( Ne pas oublier de <i color=\"blue\"> Home </i> la CNC) <br>\nØ permets de tare l'axe de la CNC");
        jLabel56.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jPanel16.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Status", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel16.setToolTipText("Les informations de la CNC en temps réel sont affichées ici. En appuyant sur |Ø|, l'axe est remis à 0. ");
        jPanel16.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonResetWorkPosition4.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonResetWorkPosition4.setForeground(new java.awt.Color(255, 255, 255));
        jButtonResetWorkPosition4.setText("Ø  Zero Work Position");
        jButtonResetWorkPosition4.setToolTipText("Reset the Work Position to 0,0,0");
        jButtonResetWorkPosition4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonResetWorkPosition4ActionPerformed(evt);
            }
        });
        jPanel16.add(jButtonResetWorkPosition4, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 240, 32));

        jPanel17.setLayout(new java.awt.GridLayout(1, 0));

        jLabel57.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabel57.setForeground(new java.awt.Color(0, 75, 127));
        jPanel17.add(jLabel57);

        jPanel16.add(jPanel17, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 70, 270, -1));

        jLabelWorkPositionZ4.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionZ4.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionZ4.setText("Z0");
        jLabelWorkPositionZ4.setToolTipText("Z Work Position");
        jLabelWorkPositionZ4.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionZ4MouseClicked(evt);
            }
        });
        jPanel16.add(jLabelWorkPositionZ4, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 90, 100, 20));

        jLabelWorkPositionX4.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionX4.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionX4.setText("X0");
        jLabelWorkPositionX4.setToolTipText("X Work Position");
        jLabelWorkPositionX4.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionX4MouseClicked(evt);
            }
        });
        jPanel16.add(jLabelWorkPositionX4, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 30, 100, 20));

        jLabelWorkPositionY4.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionY4.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionY4.setText("Y0");
        jLabelWorkPositionY4.setToolTipText("Y Work Position");
        jLabelWorkPositionY4.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionY4MouseClicked(evt);
            }
        });
        jPanel16.add(jLabelWorkPositionY4, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 60, 100, 20));

        jLabelMachinePositionZ4.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionZ4.setText("Z0");
        jLabelMachinePositionZ4.setToolTipText("Z Machine Position");
        jPanel16.add(jLabelMachinePositionZ4, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 90, 60, 20));

        jLabelMachinePositionX4.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionX4.setText("X0");
        jLabelMachinePositionX4.setToolTipText("X Machine Position");
        jPanel16.add(jLabelMachinePositionX4, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 30, 60, 20));

        jLabelMachinePositionY4.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionY4.setText("Y0");
        jLabelMachinePositionY4.setToolTipText("Y Machine Position");
        jPanel16.add(jLabelMachinePositionY4, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 60, 60, 20));

        jLabel58.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel58.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel58.setText("Z:");
        jPanel16.add(jLabel58, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 90, 20, 20));

        jLabel59.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel59.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel59.setText("X:");
        jPanel16.add(jLabel59, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 30, 20, 20));

        jLabel60.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel60.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel60.setText("Y:");
        jPanel16.add(jLabel60, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 60, 20, 20));

        jButton15.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton15.setText("Ø");
        jButton15.setToolTipText("Click to Zero Z Work Position");
        jButton15.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton15ActionPerformed(evt);
            }
        });
        jPanel16.add(jButton15, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 30, 28));

        jButton16.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton16.setText("Ø");
        jButton16.setToolTipText("Click to Zero X Work Position");
        jButton16.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton16ActionPerformed(evt);
            }
        });
        jPanel16.add(jButton16, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 30, 30, 28));

        jButton17.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton17.setText("Ø");
        jButton17.setToolTipText("Click to Zero Y Work Position");
        jButton17.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton17ActionPerformed(evt);
            }
        });
        jPanel16.add(jButton17, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 60, 30, 28));

        jLabelRowsInFile11.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRowsInFile11.setText("Semi Auto Tool Change:");
        jPanel16.add(jLabelRowsInFile11, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 210, 150, 20));

        jLabelSemiAutoToolChangeStatus4.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabelSemiAutoToolChangeStatus4.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelSemiAutoToolChangeStatus4.setText("Off");
        jPanel16.add(jLabelSemiAutoToolChangeStatus4, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 210, 80, 20));

        jLabel61.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel61.setText("Feedrate:");
        jPanel16.add(jLabel61, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 170, 150, 20));

        jLabelRealTimeFeedRate7.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate7.setText("0mm/min");
        jPanel16.add(jLabelRealTimeFeedRate7, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 170, 80, 20));

        jLabel62.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel62.setText("Spindle RPM:");
        jPanel16.add(jLabel62, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 190, 150, 20));

        jLabelRealTimeSpindleRPM7.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM7.setText("0");
        jPanel16.add(jLabelRealTimeSpindleRPM7, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 190, 80, 20));

        jLabel63.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel63.setText("Feedrate:");

        jLabelRealTimeFeedRate8.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate8.setText("0mm/min");

        jLabel64.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel64.setText("Spindle RPM:");

        jLabelRealTimeSpindleRPM8.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM8.setText("0");

        jLabel65.setText("<html>\nLa vitesse de découpe et de rotation doivent être ajustées en fonction du matériel de découpe et du fôrêt utilisé.<br>\n</html>\n");

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

        javax.swing.GroupLayout jDialogTab2Layout = new javax.swing.GroupLayout(jDialogTab2.getContentPane());
        jDialogTab2.getContentPane().setLayout(jDialogTab2Layout);
        jDialogTab2Layout.setHorizontalGroup(
            jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSeparator12)
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator13)
                    .addGroup(jDialogTab2Layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel65, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                            .addComponent(jLabel55, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel56))))
                .addContainerGap())
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogTab2Layout.createSequentialGroup()
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jDialogTab2Layout.createSequentialGroup()
                                .addComponent(jLabel63, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(10, 10, 10)
                                .addComponent(jLabelRealTimeFeedRate8, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jDialogTab2Layout.createSequentialGroup()
                                .addComponent(jLabel64, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(10, 10, 10)
                                .addComponent(jLabelRealTimeSpindleRPM8, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jDialogTab2Layout.createSequentialGroup()
                        .addGap(56, 56, 56)
                        .addComponent(jPanel16, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(62, 62, 62))
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addGap(102, 102, 102)
                .addComponent(jButtonOk3, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel3, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(102, 102, 102))
        );
        jDialogTab2Layout.setVerticalGroup(
            jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel55, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator12, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel16, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator13, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel56, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(30, 30, 30)
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel63, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeFeedRate8, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel64, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeSpindleRPM8, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel65, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialogTab2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk3)
                    .addComponent(jButtonCancel3))
                .addContainerGap())
        );

        jDialogTab3.setModal(true);
        jDialogTab3.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel66.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel66.setText("<html> <h3 text-decoration=\"bold\"> Interface de Connection");
        jLabel66.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel67.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel67.setText("<html> <h4 text-align=\"center\">Cette interface permets de voir laposition de la CNC </h4>  \nLa position des axes XYZ est affichée. ( Ne pas oublier de <i color=\"blue\"> Home </i> la CNC) <br>\nØ permets de tare l'axe de la CNC");
        jLabel67.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jPanel18.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Status", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel18.setToolTipText("Les informations de la CNC en temps réel sont affichées ici. En appuyant sur |Ø|, l'axe est remis à 0. ");
        jPanel18.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonResetWorkPosition5.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonResetWorkPosition5.setForeground(new java.awt.Color(255, 255, 255));
        jButtonResetWorkPosition5.setText("Ø  Zero Work Position");
        jButtonResetWorkPosition5.setToolTipText("Reset the Work Position to 0,0,0");
        jButtonResetWorkPosition5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonResetWorkPosition5ActionPerformed(evt);
            }
        });
        jPanel18.add(jButtonResetWorkPosition5, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 240, 32));

        jPanel19.setLayout(new java.awt.GridLayout(1, 0));

        jLabel68.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabel68.setForeground(new java.awt.Color(0, 75, 127));
        jPanel19.add(jLabel68);

        jPanel18.add(jPanel19, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 70, 270, -1));

        jLabelWorkPositionZ5.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionZ5.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionZ5.setText("Z0");
        jLabelWorkPositionZ5.setToolTipText("Z Work Position");
        jLabelWorkPositionZ5.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionZ5MouseClicked(evt);
            }
        });
        jPanel18.add(jLabelWorkPositionZ5, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 90, 100, 20));

        jLabelWorkPositionX5.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionX5.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionX5.setText("X0");
        jLabelWorkPositionX5.setToolTipText("X Work Position");
        jLabelWorkPositionX5.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionX5MouseClicked(evt);
            }
        });
        jPanel18.add(jLabelWorkPositionX5, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 30, 100, 20));

        jLabelWorkPositionY5.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionY5.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionY5.setText("Y0");
        jLabelWorkPositionY5.setToolTipText("Y Work Position");
        jLabelWorkPositionY5.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionY5MouseClicked(evt);
            }
        });
        jPanel18.add(jLabelWorkPositionY5, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 60, 100, 20));

        jLabelMachinePositionZ5.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionZ5.setText("Z0");
        jLabelMachinePositionZ5.setToolTipText("Z Machine Position");
        jPanel18.add(jLabelMachinePositionZ5, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 90, 60, 20));

        jLabelMachinePositionX5.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionX5.setText("X0");
        jLabelMachinePositionX5.setToolTipText("X Machine Position");
        jPanel18.add(jLabelMachinePositionX5, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 30, 60, 20));

        jLabelMachinePositionY5.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionY5.setText("Y0");
        jLabelMachinePositionY5.setToolTipText("Y Machine Position");
        jPanel18.add(jLabelMachinePositionY5, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 60, 60, 20));

        jLabel69.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel69.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel69.setText("Z:");
        jPanel18.add(jLabel69, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 90, 20, 20));

        jLabel70.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel70.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel70.setText("X:");
        jPanel18.add(jLabel70, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 30, 20, 20));

        jLabel71.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel71.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel71.setText("Y:");
        jPanel18.add(jLabel71, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 60, 20, 20));

        jButton18.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton18.setText("Ø");
        jButton18.setToolTipText("Click to Zero Z Work Position");
        jButton18.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton18ActionPerformed(evt);
            }
        });
        jPanel18.add(jButton18, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 30, 28));

        jButton19.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton19.setText("Ø");
        jButton19.setToolTipText("Click to Zero X Work Position");
        jButton19.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton19ActionPerformed(evt);
            }
        });
        jPanel18.add(jButton19, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 30, 30, 28));

        jButton20.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton20.setText("Ø");
        jButton20.setToolTipText("Click to Zero Y Work Position");
        jButton20.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton20ActionPerformed(evt);
            }
        });
        jPanel18.add(jButton20, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 60, 30, 28));

        jLabelRowsInFile12.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRowsInFile12.setText("Semi Auto Tool Change:");
        jPanel18.add(jLabelRowsInFile12, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 210, 150, 20));

        jLabelSemiAutoToolChangeStatus5.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabelSemiAutoToolChangeStatus5.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelSemiAutoToolChangeStatus5.setText("Off");
        jPanel18.add(jLabelSemiAutoToolChangeStatus5, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 210, 80, 20));

        jLabel72.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel72.setText("Feedrate:");
        jPanel18.add(jLabel72, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 170, 150, 20));

        jLabelRealTimeFeedRate9.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate9.setText("0mm/min");
        jPanel18.add(jLabelRealTimeFeedRate9, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 170, 80, 20));

        jLabel73.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel73.setText("Spindle RPM:");
        jPanel18.add(jLabel73, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 190, 150, 20));

        jLabelRealTimeSpindleRPM9.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM9.setText("0");
        jPanel18.add(jLabelRealTimeSpindleRPM9, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 190, 80, 20));

        jLabel74.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel74.setText("Feedrate:");

        jLabelRealTimeFeedRate10.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate10.setText("0mm/min");

        jLabel75.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel75.setText("Spindle RPM:");

        jLabelRealTimeSpindleRPM10.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM10.setText("0");

        jLabel76.setText("<html>\nLa vitesse de découpe et de rotation doivent être ajustées en fonction du matériel de découpe et du fôrêt utilisé.<br>\n</html>\n");

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
                            .addComponent(jLabel76, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                            .addComponent(jLabel66, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel67))))
                .addContainerGap())
            .addGroup(jDialogTab3Layout.createSequentialGroup()
                .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogTab3Layout.createSequentialGroup()
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jDialogTab3Layout.createSequentialGroup()
                                .addComponent(jLabel74, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(10, 10, 10)
                                .addComponent(jLabelRealTimeFeedRate10, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jDialogTab3Layout.createSequentialGroup()
                                .addComponent(jLabel75, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(10, 10, 10)
                                .addComponent(jLabelRealTimeSpindleRPM10, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jDialogTab3Layout.createSequentialGroup()
                        .addGap(56, 56, 56)
                        .addComponent(jPanel18, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(62, 62, 62))
            .addGroup(jDialogTab3Layout.createSequentialGroup()
                .addGap(102, 102, 102)
                .addComponent(jButtonOk4, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel4, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(102, 102, 102))
        );
        jDialogTab3Layout.setVerticalGroup(
            jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab3Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel66, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator14, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel18, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator15, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel67, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(30, 30, 30)
                .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel74, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeFeedRate10, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel75, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeSpindleRPM10, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel76, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialogTab3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk4)
                    .addComponent(jButtonCancel4))
                .addContainerGap())
        );

        jDialogTab4.setModal(true);
        jDialogTab4.setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

        jLabel77.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel77.setText("<html> <h3 text-decoration=\"bold\"> Interface de Connection");
        jLabel77.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jLabel78.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel78.setText("<html> <h4 text-align=\"center\">Cette interface permets de voir laposition de la CNC </h4>  \nLa position des axes XYZ est affichée. ( Ne pas oublier de <i color=\"blue\"> Home </i> la CNC) <br>\nØ permets de tare l'axe de la CNC");
        jLabel78.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        jPanel20.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Status", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel20.setToolTipText("Les informations de la CNC en temps réel sont affichées ici. En appuyant sur |Ø|, l'axe est remis à 0. ");
        jPanel20.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonResetWorkPosition6.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonResetWorkPosition6.setForeground(new java.awt.Color(255, 255, 255));
        jButtonResetWorkPosition6.setText("Ø  Zero Work Position");
        jButtonResetWorkPosition6.setToolTipText("Reset the Work Position to 0,0,0");
        jButtonResetWorkPosition6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonResetWorkPosition6ActionPerformed(evt);
            }
        });
        jPanel20.add(jButtonResetWorkPosition6, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 240, 32));

        jPanel21.setLayout(new java.awt.GridLayout(1, 0));

        jLabel79.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabel79.setForeground(new java.awt.Color(0, 75, 127));
        jPanel21.add(jLabel79);

        jPanel20.add(jPanel21, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 70, 270, -1));

        jLabelWorkPositionZ6.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionZ6.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionZ6.setText("Z0");
        jLabelWorkPositionZ6.setToolTipText("Z Work Position");
        jLabelWorkPositionZ6.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionZ6MouseClicked(evt);
            }
        });
        jPanel20.add(jLabelWorkPositionZ6, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 90, 100, 20));

        jLabelWorkPositionX6.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionX6.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionX6.setText("X0");
        jLabelWorkPositionX6.setToolTipText("X Work Position");
        jLabelWorkPositionX6.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionX6MouseClicked(evt);
            }
        });
        jPanel20.add(jLabelWorkPositionX6, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 30, 100, 20));

        jLabelWorkPositionY6.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionY6.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionY6.setText("Y0");
        jLabelWorkPositionY6.setToolTipText("Y Work Position");
        jLabelWorkPositionY6.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionY6MouseClicked(evt);
            }
        });
        jPanel20.add(jLabelWorkPositionY6, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 60, 100, 20));

        jLabelMachinePositionZ6.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionZ6.setText("Z0");
        jLabelMachinePositionZ6.setToolTipText("Z Machine Position");
        jPanel20.add(jLabelMachinePositionZ6, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 90, 60, 20));

        jLabelMachinePositionX6.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionX6.setText("X0");
        jLabelMachinePositionX6.setToolTipText("X Machine Position");
        jPanel20.add(jLabelMachinePositionX6, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 30, 60, 20));

        jLabelMachinePositionY6.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionY6.setText("Y0");
        jLabelMachinePositionY6.setToolTipText("Y Machine Position");
        jPanel20.add(jLabelMachinePositionY6, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 60, 60, 20));

        jLabel80.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel80.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel80.setText("Z:");
        jPanel20.add(jLabel80, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 90, 20, 20));

        jLabel81.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel81.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel81.setText("X:");
        jPanel20.add(jLabel81, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 30, 20, 20));

        jLabel82.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabel82.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel82.setText("Y:");
        jPanel20.add(jLabel82, new org.netbeans.lib.awtextra.AbsoluteConstraints(60, 60, 20, 20));

        jButton21.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton21.setText("Ø");
        jButton21.setToolTipText("Click to Zero Z Work Position");
        jButton21.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton21ActionPerformed(evt);
            }
        });
        jPanel20.add(jButton21, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 30, 28));

        jButton22.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton22.setText("Ø");
        jButton22.setToolTipText("Click to Zero X Work Position");
        jButton22.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton22ActionPerformed(evt);
            }
        });
        jPanel20.add(jButton22, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 30, 30, 28));

        jButton23.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton23.setText("Ø");
        jButton23.setToolTipText("Click to Zero Y Work Position");
        jButton23.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton23ActionPerformed(evt);
            }
        });
        jPanel20.add(jButton23, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 60, 30, 28));

        jLabelRowsInFile13.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRowsInFile13.setText("Semi Auto Tool Change:");
        jPanel20.add(jLabelRowsInFile13, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 210, 150, 20));

        jLabelSemiAutoToolChangeStatus6.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabelSemiAutoToolChangeStatus6.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelSemiAutoToolChangeStatus6.setText("Off");
        jPanel20.add(jLabelSemiAutoToolChangeStatus6, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 210, 80, 20));

        jLabel83.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel83.setText("Feedrate:");
        jPanel20.add(jLabel83, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 170, 150, 20));

        jLabelRealTimeFeedRate11.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate11.setText("0mm/min");
        jPanel20.add(jLabelRealTimeFeedRate11, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 170, 80, 20));

        jLabel84.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel84.setText("Spindle RPM:");
        jPanel20.add(jLabel84, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 190, 150, 20));

        jLabelRealTimeSpindleRPM11.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM11.setText("0");
        jPanel20.add(jLabelRealTimeSpindleRPM11, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 190, 80, 20));

        jLabel85.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel85.setText("Feedrate:");

        jLabelRealTimeFeedRate12.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate12.setText("0mm/min");

        jLabel86.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel86.setText("Spindle RPM:");

        jLabelRealTimeSpindleRPM12.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM12.setText("0");

        jLabel87.setText("<html>\nLa vitesse de découpe et de rotation doivent être ajustées en fonction du matériel de découpe et du fôrêt utilisé.<br>\n</html>\n");

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
                            .addComponent(jLabel87, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                            .addComponent(jLabel77, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel78))))
                .addContainerGap())
            .addGroup(jDialogTab4Layout.createSequentialGroup()
                .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogTab4Layout.createSequentialGroup()
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(jDialogTab4Layout.createSequentialGroup()
                                .addComponent(jLabel85, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(10, 10, 10)
                                .addComponent(jLabelRealTimeFeedRate12, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(jDialogTab4Layout.createSequentialGroup()
                                .addComponent(jLabel86, javax.swing.GroupLayout.PREFERRED_SIZE, 150, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(10, 10, 10)
                                .addComponent(jLabelRealTimeSpindleRPM12, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jDialogTab4Layout.createSequentialGroup()
                        .addGap(56, 56, 56)
                        .addComponent(jPanel20, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(62, 62, 62))
            .addGroup(jDialogTab4Layout.createSequentialGroup()
                .addGap(102, 102, 102)
                .addComponent(jButtonOk5, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(32, 32, 32)
                .addComponent(jButtonCancel5, javax.swing.GroupLayout.PREFERRED_SIZE, 81, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(102, 102, 102))
        );
        jDialogTab4Layout.setVerticalGroup(
            jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogTab4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel77, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(5, 5, 5)
                .addComponent(jSeparator16, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel20, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator17, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(2, 2, 2)
                .addComponent(jLabel78, javax.swing.GroupLayout.PREFERRED_SIZE, 61, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(30, 30, 30)
                .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel85, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeFeedRate12, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel86, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabelRealTimeSpindleRPM12, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel87, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialogTab4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonOk5)
                    .addComponent(jButtonCancel5))
                .addContainerGap())
        );

        jFrame1.setMaximumSize(new java.awt.Dimension(930, 805));
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

        jPanel22.setBackground(new java.awt.Color(200, 200, 200));

        javax.swing.GroupLayout jPanel22Layout = new javax.swing.GroupLayout(jPanel22);
        jPanel22.setLayout(jPanel22Layout);
        jPanel22Layout.setHorizontalGroup(
            jPanel22Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 379, Short.MAX_VALUE)
        );
        jPanel22Layout.setVerticalGroup(
            jPanel22Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 250, Short.MAX_VALUE)
        );

        jPanel23.setBackground(new java.awt.Color(255, 255, 255));

        javax.swing.GroupLayout jPanel23Layout = new javax.swing.GroupLayout(jPanel23);
        jPanel23.setLayout(jPanel23Layout);
        jPanel23Layout.setHorizontalGroup(
            jPanel23Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 379, Short.MAX_VALUE)
        );
        jPanel23Layout.setVerticalGroup(
            jPanel23Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 250, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout jPanel15Layout = new javax.swing.GroupLayout(jPanel15);
        jPanel15.setLayout(jPanel15Layout);
        jPanel15Layout.setHorizontalGroup(
            jPanel15Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel15Layout.createSequentialGroup()
                .addComponent(jPanel22, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 30, Short.MAX_VALUE)
                .addComponent(jPanel23, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        jPanel15Layout.setVerticalGroup(
            jPanel15Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel22, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(jPanel23, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout jPanel14Layout = new javax.swing.GroupLayout(jPanel14);
        jPanel14.setLayout(jPanel14Layout);
        jPanel14Layout.setHorizontalGroup(
            jPanel14Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel14Layout.createSequentialGroup()
                .addComponent(jPanel15, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 0, Short.MAX_VALUE))
        );
        jPanel14Layout.setVerticalGroup(
            jPanel14Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel15, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );

        jLabel38.setText("<html>Ce texte explique comment placer le bois avec les 2 pinces pour le tenir et comment indiquer a la CNC ou est la position de travail <br><br>\n\nUne fois que la piece est positionnée, il faut définir la zone de travail pour que la CNC sache ou se positionner. ");

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
                .addGroup(jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jPanel14, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                        .addGap(227, 227, 227)
                        .addComponent(jButton12)
                        .addGap(18, 18, 18)
                        .addComponent(jButton13, javax.swing.GroupLayout.PREFERRED_SIZE, 147, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
            .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                .addGap(128, 128, 128)
                .addComponent(jLabel38, javax.swing.GroupLayout.PREFERRED_SIZE, 543, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialogMiseEnPlaceLayout.setVerticalGroup(
            jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogMiseEnPlaceLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel14, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(29, 29, 29)
                .addComponent(jLabel38, javax.swing.GroupLayout.PREFERRED_SIZE, 118, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(jDialogMiseEnPlaceLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton12, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton13, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(28, Short.MAX_VALUE))
        );

        jDialogZeroAxes.setName("jDialogMiseEnPlace"); // NOI18N

        jPanel27.setBackground(new java.awt.Color(255, 255, 255));

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
                        .addGap(0, 1, Short.MAX_VALUE))))
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
                .addContainerGap(12, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout jPanel27Layout = new javax.swing.GroupLayout(jPanel27);
        jPanel27.setLayout(jPanel27Layout);
        jPanel27Layout.setHorizontalGroup(
            jPanel27Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 576, Short.MAX_VALUE)
            .addGroup(jPanel27Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(jPanel27Layout.createSequentialGroup()
                    .addGap(150, 150, 150)
                    .addComponent(jPanelMachineControl1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addContainerGap(165, Short.MAX_VALUE)))
        );
        jPanel27Layout.setVerticalGroup(
            jPanel27Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 284, Short.MAX_VALUE)
            .addGroup(jPanel27Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(jPanel27Layout.createSequentialGroup()
                    .addContainerGap()
                    .addComponent(jPanelMachineControl1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
        );

        javax.swing.GroupLayout jPanel25Layout = new javax.swing.GroupLayout(jPanel25);
        jPanel25.setLayout(jPanel25Layout);
        jPanel25Layout.setHorizontalGroup(
            jPanel25Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel25Layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jPanel27, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel25Layout.setVerticalGroup(
            jPanel25Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel25Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel27, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        javax.swing.GroupLayout jPanel24Layout = new javax.swing.GroupLayout(jPanel24);
        jPanel24.setLayout(jPanel24Layout);
        jPanel24Layout.setHorizontalGroup(
            jPanel24Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel24Layout.createSequentialGroup()
                .addComponent(jPanel25, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );
        jPanel24Layout.setVerticalGroup(
            jPanel24Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel25, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
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
                .addContainerGap(14, Short.MAX_VALUE))
        );

        jPanel29.add(jPanelGCodeFile2, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 20, 440, 70));

        javax.swing.GroupLayout jDialogImportGCodeLayout = new javax.swing.GroupLayout(jDialogImportGCode.getContentPane());
        jDialogImportGCode.getContentPane().setLayout(jDialogImportGCodeLayout);
        jDialogImportGCodeLayout.setHorizontalGroup(
            jDialogImportGCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jButton27, javax.swing.GroupLayout.PREFERRED_SIZE, 147, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addComponent(jPanel26, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jDialogImportGCodeLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jPanel29, javax.swing.GroupLayout.PREFERRED_SIZE, 495, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialogImportGCodeLayout.setVerticalGroup(
            jDialogImportGCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialogImportGCodeLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel26, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel29, javax.swing.GroupLayout.PREFERRED_SIZE, 154, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 95, Short.MAX_VALUE)
                .addComponent(jButton27, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(37, 37, 37))
        );

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("SourceRabbit GCODE Sender");

        jPanel1.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "G-Code File", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel1.setToolTipText("Ce panneau permets d'importer et visualiser les détails de découpe du fichier. ");
        jPanel1.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jLabelRowsInFile.setText("0");
        jPanel1.add(jLabelRowsInFile, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 90, 54, -1));

        jLabelRowsInFile1.setText("Sent Rows:");
        jPanel1.add(jLabelRowsInFile1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 110, 80, -1));

        jLabelRowsInFile2.setText("Remaining Rows:");
        jPanel1.add(jLabelRowsInFile2, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 130, 100, -1));

        jLabelRowsInFile3.setText("Rows in file:");
        jPanel1.add(jLabelRowsInFile3, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 80, -1));

        jLabelSentRows.setText("0");
        jPanel1.add(jLabelSentRows, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 110, 54, -1));

        jLabelRemainingRows.setText("0");
        jPanel1.add(jLabelRemainingRows, new org.netbeans.lib.awtextra.AbsoluteConstraints(130, 130, 54, -1));

        jLabelRowsInFile4.setText("Time elapsed:");
        jPanel1.add(jLabelRowsInFile4, new org.netbeans.lib.awtextra.AbsoluteConstraints(220, 90, -1, -1));

        jLabelTimeElapsed.setText("00:00:00");
        jPanel1.add(jLabelTimeElapsed, new org.netbeans.lib.awtextra.AbsoluteConstraints(300, 90, 146, -1));

        jLabelRowsInFile5.setText("Progress:");
        jPanel1.add(jLabelRowsInFile5, new org.netbeans.lib.awtextra.AbsoluteConstraints(220, 110, 66, -1));

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

        jLabel5.setText("File:");

        jButtonGCodePause.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodePause.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodePause.setText("Pause");
        jButtonGCodePause.setEnabled(false);
        jButtonGCodePause.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodePauseActionPerformed(evt);
            }
        });

        jButtonGCodeSend.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeSend.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeSend.setText("Send");
        jButtonGCodeSend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeSendActionPerformed(evt);
            }
        });

        jButtonGCodeCancel.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeCancel.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeCancel.setText("Cancel");
        jButtonGCodeCancel.setEnabled(false);
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

        jButtonBrowse.setText("Browse");
        jButtonBrowse.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonBrowseActionPerformed(evt);
            }
        });

        jButtonVisualise.setText("Visualize");
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
                        .addComponent(jLabel5, javax.swing.GroupLayout.PREFERRED_SIZE, 26, javax.swing.GroupLayout.PREFERRED_SIZE)
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
                .addContainerGap(14, Short.MAX_VALUE))
        );

        jPanel1.add(jPanelGCodeFile, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 20, 530, 70));

        jTabbedPane1.setForeground(new java.awt.Color(255, 255, 255));
        jTabbedPane1.setToolTipText("Sur cette interface, l'utilisateur peut envoyer des commandes à la CNC, vérifier l'avancée de la découpe en temps réel et  récupérer les dernières informations. ");
        jTabbedPane1.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N

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
                .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 376, Short.MAX_VALUE)
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
        jScrollPane1.setViewportView(jTableGCodeLog);
        if (jTableGCodeLog.getColumnModel().getColumnCount() > 0) {
            jTableGCodeLog.getColumnModel().getColumn(0).setMinWidth(20);
            jTableGCodeLog.getColumnModel().getColumn(0).setPreferredWidth(20);
            jTableGCodeLog.getColumnModel().getColumn(2).setMinWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(2).setPreferredWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(2).setMaxWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(3).setMinWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(3).setPreferredWidth(50);
            jTableGCodeLog.getColumnModel().getColumn(3).setMaxWidth(50);
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
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 432, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
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
                .addContainerGap(271, Short.MAX_VALUE))
        );
        jPanelMacrosLayout.setVerticalGroup(
            jPanelMacrosLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelMacrosLayout.createSequentialGroup()
                .addGap(7, 7, 7)
                .addComponent(jLabel9)
                .addGap(3, 3, 3)
                .addComponent(jLabel10)
                .addContainerGap(430, Short.MAX_VALUE))
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
                .addContainerGap(424, Short.MAX_VALUE))
        );

        jTabbedPane1.addTab("Machine Information", jPanel7);

        jPanel6.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Vérification Paramètres de découpe", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel6.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());
        jPanel6.add(jSeparator1, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 350, 170, 10));

        jButtonConnectDisconnect7.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonConnectDisconnect7.setActionCommand("Démarrer la découpe");
        jButtonConnectDisconnect7.setLabel("MAJ les paramètres");
        jButtonConnectDisconnect7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonConnectDisconnect7ActionPerformed(evt);
            }
        });
        jPanel6.add(jButtonConnectDisconnect7, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 450, 170, -1));

        jLabel1.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        jLabel1.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        jLabel1.setText("Vitesse de rotation");
        jPanel6.add(jLabel1, new org.netbeans.lib.awtextra.AbsoluteConstraints(0, 20, 170, -1));
        jLabel1.getAccessibleContext().setAccessibleName("Vitesse de rotation\n");

        jTextPane1.setFont(new java.awt.Font("Segoe UI", 0, 10)); // NOI18N
        jTextPane1.setText("Définir ces paramètres permets de sélctionner la bonne vitesse de rotation et de découpe");
        jTextPane1.setFocusTraversalPolicyProvider(true);
        jScrollPane3.setViewportView(jTextPane1);

        jPanel6.add(jScrollPane3, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 190, 170, -1));

        jComboBox1.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "V bit", "flat bit", "drill bit", "etc bit" }));
        jComboBox1.setName(""); // NOI18N
        jPanel6.add(jComboBox1, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 320, 170, -1));

        jComboBox2.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Bois", "MDF", "Aluminium", "Plastique", "PCB", "Bronze", "Vinyle" }));
        jComboBox2.setName(""); // NOI18N
        jComboBox2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jComboBox2ActionPerformed(evt);
            }
        });
        jPanel6.add(jComboBox2, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 270, 170, -1));

        jTextPane2.setText("Vitesse de rotation :");
        jScrollPane4.setViewportView(jTextPane2);

        jPanel6.add(jScrollPane4, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 400, 130, -1));

        jLabel11.setText("Matériel");
        jPanel6.add(jLabel11, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 250, 170, -1));

        jLabel13.setText("Fraiseuse");
        jPanel6.add(jLabel13, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 300, 170, -1));
        jPanel6.add(jSeparator3, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 440, 170, 10));

        jTextPane3.setText("Vitesse de découpe :");
        jScrollPane5.setViewportView(jTextPane3);

        jPanel6.add(jScrollPane5, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 370, 130, -1));

        jLabelRecommendedRPM.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRecommendedRPM.setText("0");
        jPanel6.add(jLabelRecommendedRPM, new org.netbeans.lib.awtextra.AbsoluteConstraints(150, 370, 30, 20));

        jPanel2.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Status", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanel2.setToolTipText("Les informations de la CNC en temps réel sont affichées ici. En appuyant sur |Ø|, l'axe est remis à 0. ");
        jPanel2.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonResetWorkPosition.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonResetWorkPosition.setForeground(new java.awt.Color(255, 255, 255));
        jButtonResetWorkPosition.setText("Ø  Zero Work Position");
        jButtonResetWorkPosition.setToolTipText("Reset the Work Position to 0,0,0");
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
        jLabelWorkPositionZ.setToolTipText("Z Work Position");
        jLabelWorkPositionZ.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionZMouseClicked(evt);
            }
        });
        jPanel2.add(jLabelWorkPositionZ, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 90, 100, 20));

        jLabelWorkPositionX.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionX.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionX.setText("X0");
        jLabelWorkPositionX.setToolTipText("X Work Position");
        jLabelWorkPositionX.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionXMouseClicked(evt);
            }
        });
        jPanel2.add(jLabelWorkPositionX, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 30, 100, 20));

        jLabelWorkPositionY.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        jLabelWorkPositionY.setForeground(new java.awt.Color(255, 255, 255));
        jLabelWorkPositionY.setText("Y0");
        jLabelWorkPositionY.setToolTipText("Y Work Position");
        jLabelWorkPositionY.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                jLabelWorkPositionYMouseClicked(evt);
            }
        });
        jPanel2.add(jLabelWorkPositionY, new org.netbeans.lib.awtextra.AbsoluteConstraints(90, 60, 100, 20));

        jLabelMachinePositionZ.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionZ.setText("Z0");
        jLabelMachinePositionZ.setToolTipText("Z Machine Position");
        jPanel2.add(jLabelMachinePositionZ, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 90, 60, 20));

        jLabelMachinePositionX.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionX.setText("X0");
        jLabelMachinePositionX.setToolTipText("X Machine Position");
        jPanel2.add(jLabelMachinePositionX, new org.netbeans.lib.awtextra.AbsoluteConstraints(200, 30, 60, 20));

        jLabelMachinePositionY.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelMachinePositionY.setText("Y0");
        jLabelMachinePositionY.setToolTipText("Y Machine Position");
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
        jButton1.setToolTipText("Click to Zero Z Work Position");
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton1, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 90, 30, 28));

        jButton2.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton2.setText("Ø");
        jButton2.setToolTipText("Click to Zero X Work Position");
        jButton2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton2ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton2, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 30, 30, 28));

        jButton3.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButton3.setText("Ø");
        jButton3.setToolTipText("Click to Zero Y Work Position");
        jButton3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton3ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton3, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 60, 30, 28));

        jLabelRowsInFile7.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRowsInFile7.setText("Semi Auto Tool Change:");
        jPanel2.add(jLabelRowsInFile7, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 210, 150, 20));

        jLabelSemiAutoToolChangeStatus.setFont(new java.awt.Font("Tahoma", 1, 12)); // NOI18N
        jLabelSemiAutoToolChangeStatus.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelSemiAutoToolChangeStatus.setText("Off");
        jPanel2.add(jLabelSemiAutoToolChangeStatus, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 210, 80, 20));

        jLabel14.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel14.setText("Feedrate:");
        jPanel2.add(jLabel14, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 170, 150, 20));

        jLabelRealTimeFeedRate.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeFeedRate.setText("0mm/min");
        jPanel2.add(jLabelRealTimeFeedRate, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 170, 80, 20));

        jLabel15.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabel15.setText("Spindle RPM:");
        jPanel2.add(jLabel15, new org.netbeans.lib.awtextra.AbsoluteConstraints(20, 190, 150, 20));

        jLabelRealTimeSpindleRPM.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jLabelRealTimeSpindleRPM.setText("0");
        jPanel2.add(jLabelRealTimeSpindleRPM, new org.netbeans.lib.awtextra.AbsoluteConstraints(180, 190, 80, 20));

        jPanelMachineControl.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Machine Control", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelMachineControl.setToolTipText("Ce panneau de controle permets de déplacer la CNC en temps réel. Il est possible d'ajuster le pas avec la glissière.");

        jRadioButtonInches.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonInches.setText("inch");
        jRadioButtonInches.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonInchesActionPerformed(evt);
            }
        });

        jRadioButtonMillimeters.setForeground(new java.awt.Color(255, 255, 255));
        jRadioButtonMillimeters.setSelected(true);
        jRadioButtonMillimeters.setText("mm");
        jRadioButtonMillimeters.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRadioButtonMillimetersActionPerformed(evt);
            }
        });

        jLabel4.setForeground(new java.awt.Color(255, 255, 255));
        jLabel4.setText("Step Size:");

        jSpinnerStep.setModel(new javax.swing.SpinnerNumberModel(1.0d, 0.009999999776482582d, null, 0.009999999776482582d));
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
        jCheckBoxEnableKeyboardJogging.setText("Enable Keyboard Jogging");
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
                        .addGap(0, 9, Short.MAX_VALUE)
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
        jSliderStepSize.setValue(3);
        jSliderStepSize.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jSliderStepSizeStateChanged(evt);
            }
        });

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
                        .addComponent(jLabel4)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(jSpinnerStep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jRadioButtonInches)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jRadioButtonMillimeters)
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
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanelConnection.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Connection", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Tahoma", 1, 12), new java.awt.Color(255, 255, 255))); // NOI18N
        jPanelConnection.setToolTipText("Cette interface permets de vérifier l'état de la connection avec la CNC. \\n Lorsqu'une erreur intervient, la CNC se mets en état \"Arlarm\". Pour continuer ou recommencer la découpe, cliquez sur \"Kill Alarm\", puis \"Soft Reset\". \\n En cas d'erreur de connection, cliquez sur \"Disconnect\" et changez le paramètres de connection dans System --> GRBL Settings");
        jPanelConnection.setVerifyInputWhenFocusTarget(false);
        jPanelConnection.setLayout(new org.netbeans.lib.awtextra.AbsoluteLayout());

        jButtonSoftReset.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonSoftReset.setText("Soft Reset");
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
        jButtonKillAlarm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonKillAlarmActionPerformed(evt);
            }
        });
        jPanelConnection.add(jButtonKillAlarm, new org.netbeans.lib.awtextra.AbsoluteConstraints(190, 20, -1, -1));

        jLabelActiveState.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jLabelActiveState.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabelActiveState.setText("Restarting...");
        jPanelConnection.add(jLabelActiveState, new org.netbeans.lib.awtextra.AbsoluteConstraints(70, 20, 120, 20));

        jButtonConnectDisconnect1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonConnectDisconnect1.setText("Disconnect");
        jButtonConnectDisconnect1.setName("jButtonConnectDisconnect"); // NOI18N
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
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jLayeredPane1Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jLayeredPane1Layout.createSequentialGroup()
                        .addGroup(jLayeredPane1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jPanelConnection, javax.swing.GroupLayout.PREFERRED_SIZE, 282, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jPanelMachineControl, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jLayeredPane1Layout.setVerticalGroup(
            jLayeredPane1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jLayeredPane1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanelConnection, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanelMachineControl, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(20, Short.MAX_VALUE))
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
        jMenuItem5.setLabel("Tutorial");
        jMenuItem5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem5ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem5);

        jMenuItem9.setText("Tutoriel découpe complet");
        jMenuItem9.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem9ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem9);

        jMenuItem10.setText("Guide conversion G Code");
        jMenuItem10.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem10ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem10);

        jMenuItem1.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        jMenuItem1.setText("Apprendre le G Code");
        jMenuItem1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem1ActionPerformed(evt);
            }
        });
        jMenu5.add(jMenuItem1);

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
                .addGap(0, 0, 0)
                .addComponent(jPanel6, javax.swing.GroupLayout.PREFERRED_SIZE, 188, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, 154, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTabbedPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLayeredPane1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jPanel6, javax.swing.GroupLayout.PREFERRED_SIZE, 638, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 0, Short.MAX_VALUE))))
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

    private void jMenuItem5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem5ActionPerformed
        if(tutorial){
            
        
        
        tutorial=false;
        // Vider tous les Panels sauf CONNECTION
        /* Elements de Machine Status */
        jLabelMachinePositionX.setVisible(false); jLabelRealTimeFeedRate.setVisible(false); jLabel14.setVisible(false); jLabelWorkPositionZ.setVisible(false); jLabelSemiAutoToolChangeStatus.setVisible(false); jLabelMachinePositionY.setVisible(false); jLabelRowsInFile7.setVisible(false); jLabelWorkPositionX.setVisible(false); jButtonResetWorkPosition.setVisible(false); jLabelMachinePositionZ.setVisible(false); jLabel12.setVisible(false); jLabelRealTimeSpindleRPM.setVisible(false); jLabelWorkPositionY.setVisible(false); jButton3.setVisible(false); jLabel15.setVisible(false); jButton2.setVisible(false); jLabel2.setVisible(false); jLabel3.setVisible(false); jButton1.setVisible(false);
        
        /* Elements de Machine Control */
        jRadioButtonMillimeters.setVisible(false); jButtonZMinus.setVisible(false); jCheckBoxEnableKeyboardJogging.setVisible(false); jButtonZPlus.setVisible(false); jButtonXPlus.setVisible(false); jLabel4.setVisible(false); jRadioButtonInches.setVisible(false); jSliderStepSize.setVisible(false); jLabelRemoveFocus.setVisible(false); jButtonXMinus.setVisible(false); jButtonReturnToZero.setVisible(false); jSpinnerStep.setVisible(false); jButtonYPlus.setVisible(false); jButtonYMinus.setVisible(false);      
        
        /* Elements de  GCode SENDER */
        jButtonGCodePause.setVisible(false); jLabelRowsInFile3.setVisible(false); jLabelRowsInFile2.setVisible(false); jLabelRowsInFile.setVisible(false); jLabelSentRows.setVisible(false); jButtonGCodeSend.setVisible(false); jProgressBarGCodeProgress.setVisible(false); jButtonGCodeCancel.setVisible(false); jLabelRowsInFile1.setVisible(false); jLabelRowsInFile4.setVisible(false); jLabelTimeElapsed.setVisible(false); jLabelRowsInFile5.setVisible(false); jTextFieldGCodeFile.setVisible(false); jLabel5.setVisible(false); jLabelRemainingRows.setVisible(false);
       
         /* Elements de TabbedPane */
         /* TAB 1*/
         jLabel7.setVisible(false); jTextAreaConsole.setVisible(false); jCheckBoxShowVerboseOutput.setVisible(false); jTextFieldCommand.setVisible(false); jButtonClearConsole.setVisible(false);
        /* TAB 2*/
        jTableGCodeLog.setVisible(false); jButtonClearLog.setVisible(false); jCheckBoxEnableGCodeLog.setVisible(false);
        /* TAB 3*/
        jLabel9.setVisible(false); jLabel10.setVisible(false);
        /* TAB 4*/
        jLabel16.setVisible(false); jLabelMachineHomePosition.setVisible(false); jLabelLastStatusUpdate.setVisible(false); jLabel17.setVisible(false);
        
         /***********DEBUT DU TUTORIEL *****************/
        
        jDialogLevel.pack();
        jDialogLevel.setLocationRelativeTo(null); 
        jDialogLevel.setVisible(true);
        
        
        
        jDialog1.pack();
        jDialog1.setLocationRelativeTo(null);
        jDialog1.setVisible(true);
        
        if(tutorial){ // Verifier que l'utilisateur n'as pas quitter le tuto
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
        } else{
            /* Elements de Connection Invisible*/
            jButtonSoftReset.setVisible(false); jLabelMachineX1.setVisible(false); jButtonKillAlarm.setVisible(false);jButtonConnectDisconnect1.setVisible(false); jLabelActiveState.setVisible(false);
            /* Elements de Machine Statuts Visible */
            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
            
            jDialog2.pack();
            jDialog2.setLocationRelativeTo(null);
            jDialog2.setVisible(true);
            
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
                                    jDialogTab1.pack();
                                    jDialogTab4.setLocationRelativeTo(null);
                                    jDialogTab4.setVisible(true);
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
                                            /***** FIN DU TUTO *******/
                                            /*Element Connection*/
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
        }
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

    private void jButtonResetWorkPosition4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonResetWorkPosition4ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonResetWorkPosition4ActionPerformed

    private void jLabelWorkPositionZ4MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionZ4MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionZ4MouseClicked

    private void jLabelWorkPositionX4MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionX4MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionX4MouseClicked

    private void jLabelWorkPositionY4MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionY4MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionY4MouseClicked

    private void jButton15ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton15ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton15ActionPerformed

    private void jButton16ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton16ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton16ActionPerformed

    private void jButton17ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton17ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton17ActionPerformed

    private void jButtonCancel3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel3ActionPerformed
        tutorial=true;
        jDialogTab2.dispose();
    }//GEN-LAST:event_jButtonCancel3ActionPerformed

    private void jButtonOk3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk3ActionPerformed
        jDialogTab2.dispose();
    }//GEN-LAST:event_jButtonOk3ActionPerformed

    private void jButtonResetWorkPosition5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonResetWorkPosition5ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonResetWorkPosition5ActionPerformed

    private void jLabelWorkPositionZ5MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionZ5MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionZ5MouseClicked

    private void jLabelWorkPositionX5MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionX5MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionX5MouseClicked

    private void jLabelWorkPositionY5MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionY5MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionY5MouseClicked

    private void jButton18ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton18ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton18ActionPerformed

    private void jButton19ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton19ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton19ActionPerformed

    private void jButton20ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton20ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton20ActionPerformed

    private void jButtonCancel4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel4ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonCancel4ActionPerformed

    private void jButtonOk4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk4ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonOk4ActionPerformed

    private void jButtonResetWorkPosition6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonResetWorkPosition6ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonResetWorkPosition6ActionPerformed

    private void jLabelWorkPositionZ6MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionZ6MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionZ6MouseClicked

    private void jLabelWorkPositionX6MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionX6MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionX6MouseClicked

    private void jLabelWorkPositionY6MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelWorkPositionY6MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelWorkPositionY6MouseClicked

    private void jButton21ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton21ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton21ActionPerformed

    private void jButton22ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton22ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton22ActionPerformed

    private void jButton23ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton23ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButton23ActionPerformed

    private void jButtonCancel5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonCancel5ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonCancel5ActionPerformed

    private void jButtonOk5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonOk5ActionPerformed
        // TODO add your handling code here:
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

        jDialogLevel.dispose();
    }//GEN-LAST:event_jConfirmerBouttonActionPerformed

    private void jLevelComboActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jLevelComboActionPerformed
        level = jLevelCombo.getSelectedIndex(); //niveau de l'utilisateur entre 1 et 3
        if (level == 0){
            jMenu6.setText("Débutant");
        }
        if(level == 1){
            jMenu6.setText("Intermédiaire");
        }
        if(level == 2){
            jMenu6.setText("Expert");
        }
    }//GEN-LAST:event_jLevelComboActionPerformed

    private void jMenuItem6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem6ActionPerformed
       jMenu6.setText("Débutant");
       level=1;
       checkStatus();
    }//GEN-LAST:event_jMenuItem6ActionPerformed

    private void jMenuItem7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem7ActionPerformed
        jMenu6.setText("Intermédiaire");
        level=2;
        checkStatus();
    }//GEN-LAST:event_jMenuItem7ActionPerformed

    private void jMenuItem8ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem8ActionPerformed
        jMenu6.setText("Expert");
        level=3;
        checkStatus();
    }//GEN-LAST:event_jMenuItem8ActionPerformed


private void checkStatus(){

    //debutant
    if(level == 1){
        jMenuSetWorkPos.setEnabled(false);
        jMenuItemHoleCenterFinder.setEnabled(false);
        jPanel5.setEnabled(false);
        jPanelMacros.setEnabled(false);
        jPanelMachineControl.setEnabled(false);
        jMenuItemToolChangeSettings.setEnabled(false);    
        
}
    
   //Intermédiaire
    if(level == 2){
    
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
    }//GEN-LAST:event_jButton13ActionPerformed

    private void jButton12ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton12ActionPerformed
        // TODO add your handling code here:
        frmSetWorkPosition frm = new frmSetWorkPosition(this, true);
        frm.setVisible(true);
        frm.setLocationRelativeTo(null);
        jButton13.setEnabled(true);
        
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
        jButton25.setEnabled(true);
    }//GEN-LAST:event_jButton14ActionPerformed

    private void jButton24ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton24ActionPerformed
        // TODO add your handling code here:
        jDialogImportGCode.setVisible(true);
        jDialogZeroAxes.dispose();
        
    }//GEN-LAST:event_jButton24ActionPerformed

    private void jButton25ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton25ActionPerformed
        // TODO add your handling code here:
        frmZAxisTouchProbe frm = new frmZAxisTouchProbe(this, true);
        frm.setVisible(true);
        jButton24.setEnabled(true);
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
        // TODO add your handling code here:
    }//GEN-LAST:event_jRadioButtonInches1ActionPerformed

    private void jRadioButtonMillimeters1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRadioButtonMillimeters1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jRadioButtonMillimeters1ActionPerformed

    private void jButtonYMinus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonYMinus1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonYMinus1ActionPerformed

    private void jButtonXMinus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonXMinus1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonXMinus1ActionPerformed

    private void jButtonYPlus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonYPlus1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonYPlus1ActionPerformed

    private void jButtonXPlus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonXPlus1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonXPlus1ActionPerformed

    private void jButtonZPlus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonZPlus1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonZPlus1ActionPerformed

    private void jButtonZMinus1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonZMinus1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonZMinus1ActionPerformed

    private void jCheckBoxEnableKeyboardJogging1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBoxEnableKeyboardJogging1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jCheckBoxEnableKeyboardJogging1ActionPerformed

    private void jLabelRemoveFocus1MouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_jLabelRemoveFocus1MouseClicked
        // TODO add your handling code here:
    }//GEN-LAST:event_jLabelRemoveFocus1MouseClicked

    private void jButtonReturnToZero1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonReturnToZero1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonReturnToZero1ActionPerformed

    private void jSliderStepSize1StateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_jSliderStepSize1StateChanged
        // TODO add your handling code here:
    }//GEN-LAST:event_jSliderStepSize1StateChanged


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButton10;
    private javax.swing.JButton jButton11;
    private javax.swing.JButton jButton12;
    private javax.swing.JButton jButton13;
    private javax.swing.JButton jButton14;
    private javax.swing.JButton jButton15;
    private javax.swing.JButton jButton16;
    private javax.swing.JButton jButton17;
    private javax.swing.JButton jButton18;
    private javax.swing.JButton jButton19;
    private javax.swing.JButton jButton2;
    private javax.swing.JButton jButton20;
    private javax.swing.JButton jButton21;
    private javax.swing.JButton jButton22;
    private javax.swing.JButton jButton23;
    private javax.swing.JButton jButton24;
    private javax.swing.JButton jButton25;
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
    private javax.swing.JButton jButtonCancel;
    private javax.swing.JButton jButtonCancel1;
    private javax.swing.JButton jButtonCancel2;
    private javax.swing.JButton jButtonCancel3;
    private javax.swing.JButton jButtonCancel4;
    private javax.swing.JButton jButtonCancel5;
    private javax.swing.JButton jButtonClearConsole;
    private javax.swing.JButton jButtonClearConsole1;
    private javax.swing.JButton jButtonClearLog;
    private javax.swing.JButton jButtonConnectDisconnect1;
    private javax.swing.JButton jButtonConnectDisconnect2;
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
    private javax.swing.JButton jButtonOk;
    private javax.swing.JButton jButtonOk1;
    private javax.swing.JButton jButtonOk2;
    private javax.swing.JButton jButtonOk3;
    private javax.swing.JButton jButtonOk4;
    private javax.swing.JButton jButtonOk5;
    private javax.swing.JButton jButtonResetWorkPosition;
    private javax.swing.JButton jButtonResetWorkPosition1;
    private javax.swing.JButton jButtonResetWorkPosition4;
    private javax.swing.JButton jButtonResetWorkPosition5;
    private javax.swing.JButton jButtonResetWorkPosition6;
    private javax.swing.JButton jButtonReturnToZero;
    private javax.swing.JButton jButtonReturnToZero1;
    private javax.swing.JButton jButtonSoftReset;
    private javax.swing.JButton jButtonSoftReset1;
    private javax.swing.JButton jButtonSoftReset2;
    private javax.swing.JButton jButtonVisualise;
    private javax.swing.JButton jButtonVisualise1;
    private javax.swing.JButton jButtonXMinus;
    private javax.swing.JButton jButtonXMinus1;
    private javax.swing.JButton jButtonXPlus;
    private javax.swing.JButton jButtonXPlus1;
    private javax.swing.JButton jButtonYMinus;
    private javax.swing.JButton jButtonYMinus1;
    private javax.swing.JButton jButtonYPlus;
    private javax.swing.JButton jButtonYPlus1;
    private javax.swing.JButton jButtonZMinus;
    private javax.swing.JButton jButtonZMinus1;
    private javax.swing.JButton jButtonZPlus;
    private javax.swing.JButton jButtonZPlus1;
    private javax.swing.JCheckBox jCheckBoxEnableGCodeLog;
    private javax.swing.JCheckBox jCheckBoxEnableKeyboardJogging;
    private javax.swing.JCheckBox jCheckBoxEnableKeyboardJogging1;
    private javax.swing.JCheckBox jCheckBoxShowVerboseOutput;
    private javax.swing.JCheckBox jCheckBoxShowVerboseOutput1;
    private javax.swing.JComboBox<String> jComboBox1;
    private javax.swing.JComboBox<String> jComboBox2;
    private javax.swing.JButton jConfirmerBoutton;
    private javax.swing.JDialog jDialog1;
    private javax.swing.JDialog jDialog2;
    private javax.swing.JDialog jDialogGSender;
    private javax.swing.JDialog jDialogImportGCode;
    private javax.swing.JDialog jDialogLevel;
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
    private javax.swing.JLabel jLabel44;
    private javax.swing.JLabel jLabel45;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel55;
    private javax.swing.JLabel jLabel56;
    private javax.swing.JLabel jLabel57;
    private javax.swing.JLabel jLabel58;
    private javax.swing.JLabel jLabel59;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel60;
    private javax.swing.JLabel jLabel61;
    private javax.swing.JLabel jLabel62;
    private javax.swing.JLabel jLabel63;
    private javax.swing.JLabel jLabel64;
    private javax.swing.JLabel jLabel65;
    private javax.swing.JLabel jLabel66;
    private javax.swing.JLabel jLabel67;
    private javax.swing.JLabel jLabel68;
    private javax.swing.JLabel jLabel69;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel70;
    private javax.swing.JLabel jLabel71;
    private javax.swing.JLabel jLabel72;
    private javax.swing.JLabel jLabel73;
    private javax.swing.JLabel jLabel74;
    private javax.swing.JLabel jLabel75;
    private javax.swing.JLabel jLabel76;
    private javax.swing.JLabel jLabel77;
    private javax.swing.JLabel jLabel78;
    private javax.swing.JLabel jLabel79;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel80;
    private javax.swing.JLabel jLabel81;
    private javax.swing.JLabel jLabel82;
    private javax.swing.JLabel jLabel83;
    private javax.swing.JLabel jLabel84;
    private javax.swing.JLabel jLabel85;
    private javax.swing.JLabel jLabel86;
    private javax.swing.JLabel jLabel87;
    private javax.swing.JLabel jLabel88;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JLabel jLabelActiveState;
    private javax.swing.JLabel jLabelActiveState1;
    private javax.swing.JLabel jLabelLastStatusUpdate;
    private javax.swing.JLabel jLabelMachineHomePosition;
    private javax.swing.JLabel jLabelMachinePositionX;
    private javax.swing.JLabel jLabelMachinePositionX1;
    private javax.swing.JLabel jLabelMachinePositionX4;
    private javax.swing.JLabel jLabelMachinePositionX5;
    private javax.swing.JLabel jLabelMachinePositionX6;
    private javax.swing.JLabel jLabelMachinePositionY;
    private javax.swing.JLabel jLabelMachinePositionY1;
    private javax.swing.JLabel jLabelMachinePositionY4;
    private javax.swing.JLabel jLabelMachinePositionY5;
    private javax.swing.JLabel jLabelMachinePositionY6;
    private javax.swing.JLabel jLabelMachinePositionZ;
    private javax.swing.JLabel jLabelMachinePositionZ1;
    private javax.swing.JLabel jLabelMachinePositionZ4;
    private javax.swing.JLabel jLabelMachinePositionZ5;
    private javax.swing.JLabel jLabelMachinePositionZ6;
    private javax.swing.JLabel jLabelMachineX1;
    private javax.swing.JLabel jLabelMachineX2;
    private javax.swing.JLabel jLabelRealTimeFeedRate;
    private javax.swing.JLabel jLabelRealTimeFeedRate1;
    private javax.swing.JLabel jLabelRealTimeFeedRate10;
    private javax.swing.JLabel jLabelRealTimeFeedRate11;
    private javax.swing.JLabel jLabelRealTimeFeedRate12;
    private javax.swing.JLabel jLabelRealTimeFeedRate2;
    private javax.swing.JLabel jLabelRealTimeFeedRate7;
    private javax.swing.JLabel jLabelRealTimeFeedRate8;
    private javax.swing.JLabel jLabelRealTimeFeedRate9;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM1;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM10;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM11;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM12;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM2;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM7;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM8;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM9;
    private javax.swing.JLabel jLabelRecommendedRPM;
    private javax.swing.JLabel jLabelRemainingRows;
    private javax.swing.JLabel jLabelRemainingRows1;
    private javax.swing.JLabel jLabelRemainingRows2;
    private javax.swing.JLabel jLabelRemoveFocus;
    private javax.swing.JLabel jLabelRemoveFocus1;
    private javax.swing.JLabel jLabelRowsInFile;
    private javax.swing.JLabel jLabelRowsInFile1;
    private javax.swing.JLabel jLabelRowsInFile10;
    private javax.swing.JLabel jLabelRowsInFile11;
    private javax.swing.JLabel jLabelRowsInFile12;
    private javax.swing.JLabel jLabelRowsInFile13;
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
    private javax.swing.JLabel jLabelSemiAutoToolChangeStatus4;
    private javax.swing.JLabel jLabelSemiAutoToolChangeStatus5;
    private javax.swing.JLabel jLabelSemiAutoToolChangeStatus6;
    private javax.swing.JLabel jLabelSentRows;
    private javax.swing.JLabel jLabelSentRows1;
    private javax.swing.JLabel jLabelSentRows2;
    private javax.swing.JLabel jLabelTimeElapsed;
    private javax.swing.JLabel jLabelTimeElapsed1;
    private javax.swing.JLabel jLabelWorkPositionX;
    private javax.swing.JLabel jLabelWorkPositionX1;
    private javax.swing.JLabel jLabelWorkPositionX4;
    private javax.swing.JLabel jLabelWorkPositionX5;
    private javax.swing.JLabel jLabelWorkPositionX6;
    private javax.swing.JLabel jLabelWorkPositionY;
    private javax.swing.JLabel jLabelWorkPositionY1;
    private javax.swing.JLabel jLabelWorkPositionY4;
    private javax.swing.JLabel jLabelWorkPositionY5;
    private javax.swing.JLabel jLabelWorkPositionY6;
    private javax.swing.JLabel jLabelWorkPositionZ;
    private javax.swing.JLabel jLabelWorkPositionZ1;
    private javax.swing.JLabel jLabelWorkPositionZ4;
    private javax.swing.JLabel jLabelWorkPositionZ5;
    private javax.swing.JLabel jLabelWorkPositionZ6;
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
    private javax.swing.JPanel jPanel18;
    private javax.swing.JPanel jPanel19;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel20;
    private javax.swing.JPanel jPanel21;
    private javax.swing.JPanel jPanel22;
    private javax.swing.JPanel jPanel23;
    private javax.swing.JPanel jPanel24;
    private javax.swing.JPanel jPanel25;
    private javax.swing.JPanel jPanel26;
    private javax.swing.JPanel jPanel27;
    private javax.swing.JPanel jPanel28;
    private javax.swing.JPanel jPanel29;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JPanel jPanel8;
    private javax.swing.JPanel jPanel9;
    private javax.swing.JPanel jPanelConnection;
    private javax.swing.JPanel jPanelConnection1;
    private javax.swing.JPanel jPanelGCodeFile;
    private javax.swing.JPanel jPanelGCodeFile1;
    private javax.swing.JPanel jPanelGCodeFile2;
    private javax.swing.JPanel jPanelJogButtons;
    private javax.swing.JPanel jPanelJogButtons1;
    private javax.swing.JPanel jPanelMachineControl;
    private javax.swing.JPanel jPanelMachineControl1;
    private javax.swing.JPanel jPanelMacros;
    private javax.swing.JProgressBar jProgressBarGCodeProgress;
    private javax.swing.JProgressBar jProgressBarGCodeProgress1;
    private javax.swing.JProgressBar jProgressBarGCodeProgress2;
    private javax.swing.JRadioButton jRadioButtonInches;
    private javax.swing.JRadioButton jRadioButtonInches1;
    private javax.swing.JRadioButton jRadioButtonMillimeters;
    private javax.swing.JRadioButton jRadioButtonMillimeters1;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JScrollPane jScrollPane4;
    private javax.swing.JScrollPane jScrollPane5;
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
    private javax.swing.JSeparator jSeparator2;
    private javax.swing.JSeparator jSeparator3;
    private javax.swing.JSeparator jSeparator4;
    private javax.swing.JSeparator jSeparator5;
    private javax.swing.JSeparator jSeparator6;
    private javax.swing.JSeparator jSeparator7;
    private javax.swing.JSeparator jSeparator8;
    private javax.swing.JSeparator jSeparator9;
    private javax.swing.JSlider jSliderStepSize;
    private javax.swing.JSlider jSliderStepSize1;
    private javax.swing.JSpinner jSpinnerStep;
    private javax.swing.JSpinner jSpinnerStep1;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JTable jTable1;
    private javax.swing.JTable jTableGCodeLog;
    private javax.swing.JTextArea jTextAreaConsole;
    private javax.swing.JTextArea jTextAreaConsole1;
    private javax.swing.JTextField jTextFieldCommand;
    private javax.swing.JTextField jTextFieldCommand1;
    private javax.swing.JTextField jTextFieldGCodeFile;
    private javax.swing.JTextField jTextFieldGCodeFile1;
    private javax.swing.JTextField jTextFieldGCodeFile2;
    private javax.swing.JTextPane jTextPane1;
    private javax.swing.JTextPane jTextPane2;
    private javax.swing.JTextPane jTextPane3;
    // End of variables declaration//GEN-END:variables
    boolean tutorial=true;
    int level;

   
}
