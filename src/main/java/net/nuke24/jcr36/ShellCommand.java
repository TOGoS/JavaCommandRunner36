package net.nuke24.jcr36;

import java.util.Arrays;

public class ShellCommand implements JCRAction {
	public static final Function<Integer, JCRAction> DEFAULT_ON_EXIT = new Function<Integer,JCRAction>() {
		@Override public JCRAction apply(Integer code) {
			return code.intValue() == 0 ? NullAction.INSTANCE : new QuitAction(code);
		}
	};
	
	public final String[] argv;
	public final Function<Integer, JCRAction> onExit;
	
	public ShellCommand(String[] argv, Function<Integer, JCRAction> onExit) {
		this.argv = argv;
		this.onExit = onExit;
	}
	
	@Override public boolean equals(Object other) {
		if( !(other instanceof ShellCommand) ) return false;
		
		ShellCommand osc = (ShellCommand)other;
		return this.onExit.equals(osc.onExit) && Arrays.equals(this.argv, osc.argv);
	}
	
	@Override public int hashCode() {
		return "ShellCommand".hashCode() +  31*Arrays.hashCode(argv) + 31*31*onExit.hashCode();
	}
}
