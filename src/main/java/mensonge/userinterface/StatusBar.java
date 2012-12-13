package mensonge.userinterface;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.Timer;

import mensonge.core.tools.ActionMessageObserver;
import mensonge.core.tools.DataBaseObserver;

public class StatusBar extends JLabel implements ActionListener, ActionMessageObserver, DataBaseObserver
{
	private static final long serialVersionUID = 8573623540967463794L;
	private static final int STATUS_BAR_HEIGHT = 16;
	/**
	 * En millisecondes
	 */
	private static final int TIMER_DELAY = 10000;
	private Timer timer;
	private GraphicalUserInterface gui;

	public StatusBar(GraphicalUserInterface graphicalUserInterface)
	{
		super();
		this.gui = graphicalUserInterface;
		setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY));
		setPreferredSize(new Dimension(this.getWidth(), STATUS_BAR_HEIGHT));
		timer = new Timer(TIMER_DELAY, this);
	}

	public void setMessage(String message)
	{
		setText(" " + message);
		repaint();
		this.timer.stop();
	}

	public void done()
	{
		this.timer.restart();
	}

	@Override
	public void actionPerformed(ActionEvent arg0)
	{
		setText("");
		this.timer.stop();
	}

	@Override
	public void onInProgressAction(String message)
	{
		this.setMessage(message);
		this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		this.getParent().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		gui.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
	}

	@Override
	public void onCompletedAction(String message)
	{
		this.setMessage(message);
		this.done();
		this.setCursor(Cursor.getDefaultCursor());
		this.getParent().setCursor(Cursor.getDefaultCursor());
		gui.setCursor(Cursor.getDefaultCursor());
	}

	@Override
	public void onUpdateDataBase()
	{
		
	}

}
