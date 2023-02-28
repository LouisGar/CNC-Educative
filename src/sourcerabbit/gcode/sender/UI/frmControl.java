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
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToolTip;
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

        jDialog1 = new javax.swing.JDialog();
        jScrollPane6 = new javax.swing.JScrollPane();
        jTextPane4 = new javax.swing.JTextPane();
        jPanelConnection1 = new javax.swing.JPanel();
        jLabelMachineX2 = new javax.swing.JLabel();
        jLabelActiveState1 = new javax.swing.JLabel();
        jButtonConnectDisconnect2 = new javax.swing.JButton();
        jButtonKillAlarm2 = new javax.swing.JButton();
        jButtonKillAlarm3 = new javax.swing.JButton();
        jButtonSoftReset2 = new javax.swing.JButton();
        jScrollPane8 = new javax.swing.JScrollPane();
        jTextPane6 = new javax.swing.JTextPane();
        jScrollPane9 = new javax.swing.JScrollPane();
        jTextPane7 = new javax.swing.JTextPane();
        jButtonKillAlarm1 = new javax.swing.JButton();
        jButtonSoftReset1 = new javax.swing.JButton();
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
        jTextFieldGCodeFile = new javax.swing.JTextField();
        jLabel5 = new javax.swing.JLabel();
        jButtonGCodeBrowse = new javax.swing.JButton();
        jButtonGCodePause = new javax.swing.JButton();
        jButtonGCodeSend = new javax.swing.JButton();
        jButtonGCodeCancel = new javax.swing.JButton();
        jButtonGCodeVisualize = new javax.swing.JButton();
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
        jSeparator2 = new javax.swing.JSeparator();
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
        jMenuItem1 = new javax.swing.JMenuItem();
        jMenuItem2 = new javax.swing.JMenuItem();
        jMenuItemHoleCenterFinder = new javax.swing.JMenuItem();
        jMenu4 = new javax.swing.JMenu();
        jMenuItemStartHomingSequence = new javax.swing.JMenuItem();
        jMenuItemToolChangeSettings = new javax.swing.JMenuItem();
        jMenu3 = new javax.swing.JMenu();
        jMenuItem3 = new javax.swing.JMenuItem();
        jMenuItem4 = new javax.swing.JMenuItem();
        jMenuItem5 = new javax.swing.JMenuItem();
        jMenu6 = new javax.swing.JMenu();

        jTextPane4.setText("Cette interface permets de vérifier la connection avec la CNC.   \nLe status Idle indique aue la CNC attends des ordres\n               Working indique de la CNC effectue des ordres");
        jScrollPane6.setViewportView(jTextPane4);

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

        jTextPane6.setText("Le status Alarm indique qu'une erreur est survenue\n");
        jScrollPane8.setViewportView(jTextPane6);

        jTextPane7.setText("Cliquez sur :\n\nPuis sur : ");
        jScrollPane9.setViewportView(jTextPane7);

        jButtonKillAlarm1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonKillAlarm1.setText("Kill Alarm");

        jButtonSoftReset1.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonSoftReset1.setText("Soft Reset");

        javax.swing.GroupLayout jDialog1Layout = new javax.swing.GroupLayout(jDialog1.getContentPane());
        jDialog1.getContentPane().setLayout(jDialog1Layout);
        jDialog1Layout.setHorizontalGroup(
            jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog1Layout.createSequentialGroup()
                .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane8, javax.swing.GroupLayout.PREFERRED_SIZE, 406, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addComponent(jScrollPane9, javax.swing.GroupLayout.PREFERRED_SIZE, 91, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jButtonSoftReset1)
                            .addComponent(jButtonKillAlarm1)))
                    .addComponent(jScrollPane6, javax.swing.GroupLayout.PREFERRED_SIZE, 406, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addGap(60, 60, 60)
                        .addComponent(jPanelConnection1, javax.swing.GroupLayout.PREFERRED_SIZE, 292, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jDialog1Layout.setVerticalGroup(
            jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jDialog1Layout.createSequentialGroup()
                .addGap(36, 36, 36)
                .addComponent(jScrollPane6, javax.swing.GroupLayout.PREFERRED_SIZE, 74, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jPanelConnection1, javax.swing.GroupLayout.PREFERRED_SIZE, 85, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(jScrollPane8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jDialog1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(jScrollPane9, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jDialog1Layout.createSequentialGroup()
                        .addComponent(jButtonKillAlarm1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(jButtonSoftReset1)))
                .addContainerGap(14, Short.MAX_VALUE))
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

        jTextFieldGCodeFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jTextFieldGCodeFileActionPerformed(evt);
            }
        });

        jLabel5.setText("File:");

        jButtonGCodeBrowse.setFont(new java.awt.Font("Tahoma", 0, 12)); // NOI18N
        jButtonGCodeBrowse.setForeground(new java.awt.Color(255, 255, 255));
        jButtonGCodeBrowse.setText("Browse");
        jButtonGCodeBrowse.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonGCodeBrowseActionPerformed(evt);
            }
        });

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
                .addGap(582, 582, 582)
                .addComponent(jButtonGCodeVisualize)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jButtonGCodeBrowse, javax.swing.GroupLayout.PREFERRED_SIZE, 76, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );
        jPanelGCodeFileLayout.setVerticalGroup(
            jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanelGCodeFileLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(jTextFieldGCodeFile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanelGCodeFileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButtonGCodePause)
                    .addComponent(jButtonGCodeSend)
                    .addComponent(jButtonGCodeCancel)
                    .addComponent(jButtonGCodeVisualize)
                    .addComponent(jButtonGCodeBrowse))
                .addContainerGap(15, Short.MAX_VALUE))
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
        jPanel6.add(jLabel1, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 160, 170, -1));
        jLabel1.getAccessibleContext().setAccessibleName("Vitesse de rotation\n");

        jTextPane1.setFont(new java.awt.Font("Segoe UI", 0, 10)); // NOI18N
        jTextPane1.setText("Définir ces paramètres permets de sélctionner la bonne vitesse de rotation et de découpe");
        jTextPane1.setFocusTraversalPolicyProvider(true);
        jScrollPane3.setViewportView(jTextPane1);

        jPanel6.add(jScrollPane3, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 190, 170, -1));

        jComboBox1.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "V bit", "flat bit", "drill bit", "etc bit" }));
        jComboBox1.setName(""); // NOI18N
        jPanel6.add(jComboBox1, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 320, 170, -1));
        jPanel6.add(jSeparator2, new org.netbeans.lib.awtextra.AbsoluteConstraints(10, 150, 170, 10));

        jComboBox2.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Bois", "MDF", "Aluminium", "Plastique", "PCB", "Bronze", "Vinyle" }));
        jComboBox2.setName(""); // NOI18N
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

        jMenuItem1.setIcon(new javax.swing.ImageIcon(getClass().getResource("/sourcerabbit/gcode/sender/UI/Images/WorkArea-24x24.png"))); // NOI18N
        jMenuItem1.setText("Set Work Position");
        jMenuItem1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem1ActionPerformed(evt);
            }
        });
        jMenu2.add(jMenuItem1);

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

        jMenuItem5.setLabel("Tutorial");
        jMenuItem5.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem5ActionPerformed(evt);
            }
        });
        jMenu3.add(jMenuItem5);

        jMenuBar1.add(jMenu3);

        jMenu6.setText("Débutant");
        jMenu6.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
        jMenu6.setName("jMenuLevel"); // NOI18N
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
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
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
                    "The work position is not 0,0,0.\nDo you want to start the GCode Cycle?",
                    "Work position is not 0,0,0",
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

    private void jMenuItem1ActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_jMenuItem1ActionPerformed
    {//GEN-HEADEREND:event_jMenuItem1ActionPerformed
        frmSetWorkPosition frm = new frmSetWorkPosition(this, true);
        frm.setVisible(true);
    }//GEN-LAST:event_jMenuItem1ActionPerformed

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
        
        // jPanelStatus
        /* Elements de Machine Status */
        jLabelMachinePositionX.setVisible(false); jLabelRealTimeFeedRate.setVisible(false); jLabel14.setVisible(false); jLabelWorkPositionZ.setVisible(false); jLabelSemiAutoToolChangeStatus.setVisible(false); jLabelMachinePositionY.setVisible(false); jLabelRowsInFile7.setVisible(false); jLabelWorkPositionX.setVisible(false); jButtonResetWorkPosition.setVisible(false); jLabelMachinePositionZ.setVisible(false); jLabel12.setVisible(false); jLabelRealTimeSpindleRPM.setVisible(false); jLabelWorkPositionY.setVisible(false); jButton3.setVisible(false); jLabel15.setVisible(false); jButton2.setVisible(false); jLabel2.setVisible(false); jLabel3.setVisible(false); jButton1.setVisible(false);
        
        //jPanelMachineControl.setVisible(false);
        /* Elements de Machine Control */
        jRadioButtonMillimeters.setVisible(false); jButtonZMinus.setVisible(false); jCheckBoxEnableKeyboardJogging.setVisible(false); jButtonZPlus.setVisible(false); jButtonXPlus.setVisible(false); jLabel4.setVisible(false); jRadioButtonInches.setVisible(false); jSliderStepSize.setVisible(false); jLabelRemoveFocus.setVisible(false); jButtonXMinus.setVisible(false); jButtonReturnToZero.setVisible(false); jSpinnerStep.setVisible(false); jButtonYPlus.setVisible(false); jButtonYMinus.setVisible(false);      
        
        //jPanel1.setVisible(false);
        /* Elements de  GCode SENDER */
        jButtonGCodePause.setVisible(false); jLabelRowsInFile3.setVisible(false); jLabelRowsInFile2.setVisible(false); jLabelRowsInFile.setVisible(false); jLabelSentRows.setVisible(false); jButtonGCodeSend.setVisible(false); jProgressBarGCodeProgress.setVisible(false); jButtonGCodeCancel.setVisible(false); jLabelRowsInFile1.setVisible(false); jLabelRowsInFile4.setVisible(false); jLabelTimeElapsed.setVisible(false); jLabelRowsInFile5.setVisible(false); jTextFieldGCodeFile.setVisible(false); jLabel5.setVisible(false); jLabelRemainingRows.setVisible(false);
       
       
        //jTabbedPane1.setVisible(false);
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
        String message = jPanelConnection.getToolTipText(); // Replace "myElement" with the name of your element
        int result=0;
        // Affichage Uniquement Connection.
        jDialog1.setVisible(true);
        result = JOptionPane.showOptionDialog(jPanel2, message, "Tutoriel CNC", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
        if (result == JOptionPane.OK_OPTION) {
            /* Elements de Connection Invisible*/
            jButtonSoftReset.setVisible(false); jLabelMachineX1.setVisible(false); jButtonKillAlarm.setVisible(false);jButtonConnectDisconnect1.setVisible(false); jLabelActiveState.setVisible(false);
            /* Elements de Machine Statuts Visible */
            jLabelMachinePositionX.setVisible(true); jLabelRealTimeFeedRate.setVisible(true); jLabel14.setVisible(true); jLabelWorkPositionZ.setVisible(true); jLabelSemiAutoToolChangeStatus.setVisible(true); jLabelMachinePositionY.setVisible(true); jLabelRowsInFile7.setVisible(true); jLabelWorkPositionX.setVisible(true); jButtonResetWorkPosition.setVisible(true); jLabelMachinePositionZ.setVisible(true); jLabel12.setVisible(true); jLabelRealTimeSpindleRPM.setVisible(true); jLabelWorkPositionY.setVisible(true); jButton3.setVisible(true); jLabel15.setVisible(true); jButton2.setVisible(true); jLabel2.setVisible(true); jLabel3.setVisible(true); jButton1.setVisible(true);
            message = jPanel2.getToolTipText(); 
            result = JOptionPane.showOptionDialog(jPanelMachineControl, message, "Statut de la CNC", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
            System.out.println(result);
            if (result == JOptionPane.OK_OPTION) {
                /* Elements de Machine Control Visible*/
                jRadioButtonMillimeters.setVisible(true); jButtonZMinus.setVisible(true); jCheckBoxEnableKeyboardJogging.setVisible(true); jButtonZPlus.setVisible(true); jButtonXPlus.setVisible(true); jLabel4.setVisible(true); jRadioButtonInches.setVisible(true); jSliderStepSize.setVisible(true); jLabelRemoveFocus.setVisible(true); jButtonXMinus.setVisible(true); jButtonReturnToZero.setVisible(true); jSpinnerStep.setVisible(true); jButtonYPlus.setVisible(true); jButtonYMinus.setVisible(true);      
                /* Elements Status Inivisble */ 
                jLabelMachinePositionX.setVisible(false); jLabelRealTimeFeedRate.setVisible(false); jLabel14.setVisible(false); jLabelWorkPositionZ.setVisible(false); jLabelSemiAutoToolChangeStatus.setVisible(false); jLabelMachinePositionY.setVisible(false); jLabelRowsInFile7.setVisible(false); jLabelWorkPositionX.setVisible(false); jButtonResetWorkPosition.setVisible(false); jLabelMachinePositionZ.setVisible(false); jLabel12.setVisible(false); jLabelRealTimeSpindleRPM.setVisible(false); jLabelWorkPositionY.setVisible(false); jButton3.setVisible(false); jLabel15.setVisible(false); jButton2.setVisible(false); jLabel2.setVisible(false); jLabel3.setVisible(false); jButton1.setVisible(false);
                message=jPanelMachineControl.getToolTipText(); 
                result = JOptionPane.showOptionDialog(jPanel2, message, "Statut de la CNC", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
                if (result == JOptionPane.OK_OPTION) {
                
                
                /* Elements de Machine Control Invisible*/
                jRadioButtonMillimeters.setVisible(false); jButtonZMinus.setVisible(false); jCheckBoxEnableKeyboardJogging.setVisible(false); jButtonZPlus.setVisible(false); jButtonXPlus.setVisible(false); jLabel4.setVisible(false); jRadioButtonInches.setVisible(false); jSliderStepSize.setVisible(false); jLabelRemoveFocus.setVisible(false); jButtonXMinus.setVisible(false); jButtonReturnToZero.setVisible(false); jSpinnerStep.setVisible(false); jButtonYPlus.setVisible(false); jButtonYMinus.setVisible(false);      
               
                /* Elements de  GCode SENDER VISIBLE*/
                jButtonGCodePause.setVisible(true); jLabelRowsInFile3.setVisible(true); jLabelRowsInFile2.setVisible(true); jLabelRowsInFile.setVisible(true); jLabelSentRows.setVisible(true); jButtonGCodeSend.setVisible(true); jProgressBarGCodeProgress.setVisible(true); jButtonGCodeCancel.setVisible(true); jLabelRowsInFile1.setVisible(true); jLabelRowsInFile4.setVisible(true); jLabelTimeElapsed.setVisible(true); jLabelRowsInFile5.setVisible(true); jTextFieldGCodeFile.setVisible(true); jLabel5.setVisible(true); jLabelRemainingRows.setVisible(true);
                message = jPanel1.getToolTipText(); 
                result = JOptionPane.showOptionDialog(jPanel1, message, "Envoie du G-Code", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
                if(result == JOptionPane.OK_OPTION){
                        /* Elements de  GCode SENDER INVISIBLE */
                        jButtonGCodePause.setVisible(false); jLabelRowsInFile3.setVisible(false); jLabelRowsInFile2.setVisible(false); jLabelRowsInFile.setVisible(false); jLabelSentRows.setVisible(false); jButtonGCodeSend.setVisible(false); jProgressBarGCodeProgress.setVisible(false); jButtonGCodeCancel.setVisible(false); jLabelRowsInFile1.setVisible(false); jLabelRowsInFile4.setVisible(false); jLabelTimeElapsed.setVisible(false); jLabelRowsInFile5.setVisible(false); jTextFieldGCodeFile.setVisible(false); jLabel5.setVisible(false); jLabelRemainingRows.setVisible(false);
                        
                        /********* RESTE A FINIR TABBED PANE ******************/
                        /* Elements de TabbedPane */
                         /* TAB 1*/
                        jLabel7.setVisible(true); jTextAreaConsole.setVisible(true); jCheckBoxShowVerboseOutput.setVisible(true); jTextFieldCommand.setVisible(true); jButtonClearConsole.setVisible(true);
                        message = jPanel5.getToolTipText(); 
                        result = JOptionPane.showOptionDialog(jPanel5, message, "Envoie du G-Code", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
                        if(result == JOptionPane.OK_OPTION){
                            /* Elements de TabbedPane */
                             /* TAB 1 INVISIBLE*/
                            jLabel7.setVisible(false); jTextAreaConsole.setVisible(false); jCheckBoxShowVerboseOutput.setVisible(false); jTextFieldCommand.setVisible(false); jButtonClearConsole.setVisible(false);
                            /* TAB 2*/
                            jTableGCodeLog.setVisible(true); jButtonClearLog.setVisible(true); jCheckBoxEnableGCodeLog.setVisible(true);
                            message = jPanel4.getToolTipText(); 
                            result = JOptionPane.showOptionDialog(jPanel4, message, "Envoie du G-Code", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
                            if(result == JOptionPane.OK_OPTION){
                                /* TAB 2 INVISIBLE */
                                jTableGCodeLog.setVisible(false); jButtonClearLog.setVisible(false); jCheckBoxEnableGCodeLog.setVisible(false);
                                /* TAB 3*/
                                jLabel9.setVisible(true); jLabel10.setVisible(true);
                                message = jPanelMacros.getToolTipText(); 
                                result = JOptionPane.showOptionDialog(jPanelMacros, message, "Envoie du G-Code", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
                                if(result == JOptionPane.OK_OPTION){
                                    /* TAB 3 INVISIBLE */
                                    jLabel9.setVisible(false); jLabel10.setVisible(false);
                                    /* TAB 4*/
                                    jLabel16.setVisible(true); jLabelMachineHomePosition.setVisible(true); jLabelLastStatusUpdate.setVisible(true); jLabel17.setVisible(true);
                                    message = jPanel7.getToolTipText(); 
                                    result = JOptionPane.showOptionDialog(jPanel7, message, "Envoie du G-Code", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK"}, null);
                                     if(result == JOptionPane.OK_OPTION){
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
                                      else{
                
                tutorial=true;
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
                                 else{
                
                tutorial=true;
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
                             else{
                
                tutorial=true;
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
                         else{
                
                tutorial=true;
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
                 else{
                
                tutorial=true;
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
                 else{
                
                tutorial=true;
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
            else{
                
                tutorial=true;
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
         else{
                
                tutorial=true;
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
        else{
            tutorial=true;
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
        
        
    }//GEN-LAST:event_jMenuItem5ActionPerformed

    private void jButtonConnectDisconnect1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonConnectDisconnect1ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonConnectDisconnect1ActionPerformed

    private void jButtonConnectDisconnect7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonConnectDisconnect7ActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jButtonConnectDisconnect7ActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButton2;
    private javax.swing.JButton jButton3;
    private javax.swing.JButton jButtonClearConsole;
    private javax.swing.JButton jButtonClearLog;
    private javax.swing.JButton jButtonConnectDisconnect1;
    private javax.swing.JButton jButtonConnectDisconnect2;
    private javax.swing.JButton jButtonConnectDisconnect7;
    private javax.swing.JButton jButtonGCodeBrowse;
    private javax.swing.JButton jButtonGCodeCancel;
    private javax.swing.JButton jButtonGCodePause;
    private javax.swing.JButton jButtonGCodeSend;
    private javax.swing.JButton jButtonGCodeVisualize;
    private javax.swing.JButton jButtonKillAlarm;
    private javax.swing.JButton jButtonKillAlarm1;
    private javax.swing.JButton jButtonKillAlarm2;
    private javax.swing.JButton jButtonKillAlarm3;
    private javax.swing.JButton jButtonResetWorkPosition;
    private javax.swing.JButton jButtonReturnToZero;
    private javax.swing.JButton jButtonSoftReset;
    private javax.swing.JButton jButtonSoftReset1;
    private javax.swing.JButton jButtonSoftReset2;
    private javax.swing.JButton jButtonXMinus;
    private javax.swing.JButton jButtonXPlus;
    private javax.swing.JButton jButtonYMinus;
    private javax.swing.JButton jButtonYPlus;
    private javax.swing.JButton jButtonZMinus;
    private javax.swing.JButton jButtonZPlus;
    private javax.swing.JCheckBox jCheckBoxEnableGCodeLog;
    private javax.swing.JCheckBox jCheckBoxEnableKeyboardJogging;
    private javax.swing.JCheckBox jCheckBoxShowVerboseOutput;
    private javax.swing.JComboBox<String> jComboBox1;
    private javax.swing.JComboBox<String> jComboBox2;
    private javax.swing.JDialog jDialog1;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JLabel jLabelActiveState;
    private javax.swing.JLabel jLabelActiveState1;
    private javax.swing.JLabel jLabelLastStatusUpdate;
    private javax.swing.JLabel jLabelMachineHomePosition;
    private javax.swing.JLabel jLabelMachinePositionX;
    private javax.swing.JLabel jLabelMachinePositionY;
    private javax.swing.JLabel jLabelMachinePositionZ;
    private javax.swing.JLabel jLabelMachineX1;
    private javax.swing.JLabel jLabelMachineX2;
    private javax.swing.JLabel jLabelRealTimeFeedRate;
    private javax.swing.JLabel jLabelRealTimeSpindleRPM;
    private javax.swing.JLabel jLabelRecommendedRPM;
    private javax.swing.JLabel jLabelRemainingRows;
    private javax.swing.JLabel jLabelRemoveFocus;
    private javax.swing.JLabel jLabelRowsInFile;
    private javax.swing.JLabel jLabelRowsInFile1;
    private javax.swing.JLabel jLabelRowsInFile2;
    private javax.swing.JLabel jLabelRowsInFile3;
    private javax.swing.JLabel jLabelRowsInFile4;
    private javax.swing.JLabel jLabelRowsInFile5;
    private javax.swing.JLabel jLabelRowsInFile7;
    private javax.swing.JLabel jLabelSemiAutoToolChangeStatus;
    private javax.swing.JLabel jLabelSentRows;
    private javax.swing.JLabel jLabelTimeElapsed;
    private javax.swing.JLabel jLabelWorkPositionX;
    private javax.swing.JLabel jLabelWorkPositionY;
    private javax.swing.JLabel jLabelWorkPositionZ;
    private javax.swing.JLayeredPane jLayeredPane1;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JMenu jMenu2;
    private javax.swing.JMenu jMenu3;
    private javax.swing.JMenu jMenu4;
    private javax.swing.JMenu jMenu6;
    private javax.swing.JMenuBar jMenuBar1;
    private javax.swing.JMenuItem jMenuItem1;
    private javax.swing.JMenuItem jMenuItem2;
    private javax.swing.JMenuItem jMenuItem3;
    private javax.swing.JMenuItem jMenuItem4;
    private javax.swing.JMenuItem jMenuItem5;
    private javax.swing.JMenuItem jMenuItemExit;
    private javax.swing.JMenuItem jMenuItemGRBLSettings;
    private javax.swing.JMenuItem jMenuItemHoleCenterFinder;
    private javax.swing.JMenuItem jMenuItemStartHomingSequence;
    private javax.swing.JMenuItem jMenuItemToolChangeSettings;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JPanel jPanelConnection;
    private javax.swing.JPanel jPanelConnection1;
    private javax.swing.JPanel jPanelGCodeFile;
    private javax.swing.JPanel jPanelJogButtons;
    private javax.swing.JPanel jPanelMachineControl;
    private javax.swing.JPanel jPanelMacros;
    private javax.swing.JProgressBar jProgressBarGCodeProgress;
    private javax.swing.JRadioButton jRadioButtonInches;
    private javax.swing.JRadioButton jRadioButtonMillimeters;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JScrollPane jScrollPane4;
    private javax.swing.JScrollPane jScrollPane5;
    private javax.swing.JScrollPane jScrollPane6;
    private javax.swing.JScrollPane jScrollPane8;
    private javax.swing.JScrollPane jScrollPane9;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JSeparator jSeparator2;
    private javax.swing.JSeparator jSeparator3;
    private javax.swing.JSlider jSliderStepSize;
    private javax.swing.JSpinner jSpinnerStep;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JTable jTableGCodeLog;
    private javax.swing.JTextArea jTextAreaConsole;
    private javax.swing.JTextField jTextFieldCommand;
    private javax.swing.JTextField jTextFieldGCodeFile;
    private javax.swing.JTextPane jTextPane1;
    private javax.swing.JTextPane jTextPane2;
    private javax.swing.JTextPane jTextPane3;
    private javax.swing.JTextPane jTextPane4;
    private javax.swing.JTextPane jTextPane6;
    private javax.swing.JTextPane jTextPane7;
    // End of variables declaration//GEN-END:variables
    boolean tutorial=true;
}
