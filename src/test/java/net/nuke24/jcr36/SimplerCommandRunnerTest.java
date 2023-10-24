package net.nuke24.jcr36;

import static net.nuke24.jcr36.SimplerCommandRunner.debug;
import static net.nuke24.jcr36.SimplerCommandRunner.quote;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SimplerCommandRunnerTest implements Runnable
{
	static class OutputCollector extends PrintStream {
		ByteArrayOutputStream baos;
		Charset charset;
		public OutputCollector(ByteArrayOutputStream baos, boolean autoFlush, Charset charset) throws UnsupportedEncodingException {
			super(baos, false, charset);
			this.baos = baos;
			this.charset = charset;
		}
		public String toString() {
			return new String(baos.toByteArray(), this.charset);
		}
		
		public static OutputCollector create() {
			try {
				return new OutputCollector(new ByteArrayOutputStream(), false, Charset.forName("UTF-8"));
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	static void assertEquals(Object a, Object b) {
		if( a == b ) return;
	
		if( a == null ) throw new RuntimeException("null != "+debug(b));
		if( b == null ) throw new RuntimeException(debug(a)+" != null");
		if( !a.equals(b) ) {
			throw new RuntimeException(debug(a)+" != "+debug(b));
		}
	}
	
	static void assertTrue(boolean condition, String message) {
		if( condition ) return;
		
		throw new RuntimeException("assertTrue(false, "+quote(message)+")");
	}
	
	static Map<String,String> ENV_EMPTY = Collections.emptyMap();
	static Map<String,String> ENV_W_ALIASES = SimplerCommandRunner.withAliases(ENV_EMPTY, SimplerCommandRunner.STANDARD_ALIASES);
	
	static Map<String,String> CLEARENV_MEANS_PRINT = new HashMap<String,String>();
	static {
		CLEARENV_MEANS_PRINT.put("--clear-env", SimplerCommandRunner.CMD_PRINT);
	}
	static Map<String,String> ENV_W_CLEARENV_MEANS_PRINT = SimplerCommandRunner.withAliases(ENV_EMPTY, CLEARENV_MEANS_PRINT);
	
	static Object[] IO_NULL = new Object[] { null, null, null };
	
	public void testMainNothing() {
		assertEquals(0, SimplerCommandRunner.doJcrDoCmdMain(new String[] {}, 0, ENV_EMPTY, IO_NULL));
	}
	
	public void testPrint() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:print", "Hello, world!" }, 0, ENV_W_ALIASES, new Object[] { null, out, null });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!\n", out.toString());
	}
	
	public void testPrintN() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:print", "-n", "Hello, world!" }, 0, ENV_W_ALIASES, new Object[] { null, out, null });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!", out.toString());
	}
	
	public void testExit() {
		assertEquals(0, SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:exit" }, 0, ENV_W_ALIASES, new Object[] { null, null, null }));
	}
	
	public void testExitByLongName() {
		assertEquals(5, SimplerCommandRunner.doJcrDoCmd(new String[]{ "http://ns.nuke24.net/JavaCommandRunner36/Action/Exit", "5" }, 0, ENV_EMPTY, new Object[] { null, null, null }));
	}
	
	public void testExit123() {
		assertEquals(123, SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:exit", "123" }, 0, ENV_W_ALIASES, new Object[] { null, null, System.err }));
	}
	
	public void testExitN456() {
		assertEquals(-456, SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:exit", "-456" }, 0, ENV_W_ALIASES, new Object[] { null, null, System.err }));
	}
	
	public void testRunSysProc() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:runsys", "java", "-version" }, 0, ENV_W_ALIASES, new Object[] { null, out, out });
		assertEquals(0, exitCode);
		assertTrue(out.toString().length() > 0, "Expected `jcr:runsys java -version` to output some non-zero number of characters");
	}
	
	public void testRunSysProcToNul() {
		int exitCode = SimplerCommandRunner.doJcrDoCmd(new String[]{ "jcr:runsys", "java", "-version" }, 0, ENV_W_ALIASES, new Object[] { null, null, null });
		assertEquals(0, exitCode);
	}
	
	public void testRunJcrAsSysProc() {
		File jarFile = new File("JCR36.1.14.jar");
		if( !jarFile.exists() ) {
			System.err.println("testRunJcrAsSysProc: Skipping because "+jarFile.getPath()+" doesn't exist");
			return;
		}
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{ "jcr:runsys", "java", "-jar", jarFile.getPath(), "jcr:print", "-n", "Hello, world!" },
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!", out.toString());
	}
	
	public void testCatDataUri() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{ "jcr:cat", "data:,Hello,%20", "data:,world!" },
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!", out.toString());
	}
	
	// This one uses data URIs with non-empty type, and base64 encoding
	public void testCatDataUri2() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{ "jcr:cat", "data:text/plain,Hello,%20", "data:text/plain;base64,d29ybGQh" },
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!", out.toString());
	}
	
	public void testCatFile() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{ "jcr:cat", "./src/test/resources/hello-world.txt" },
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!\n", out.toString());
	}
	
	public void testCatFileUri() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{ "jcr:cat",
				"file:src/test/resources/hello-world.txt",
				"file:./src/test/resources/hello-world.txt"
			},
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("Hello, world!\nHello, world!\n", out.toString());
	}
	
	public void testCatEnvUris() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{
				"jcr:docmd", "foo=abc", "bar=xyz", //"--clear-env", "--env-from=src/test/resources/foo.env",
				SimplerCommandRunner.CMD_CAT, "data:,[", "x-jcr36-env:foo", "x-jcr36-env:bar", "data:,]"
			},
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("[abcxyz]", out.toString());
	}
	
	public void testLoadAndCatEnvUris() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{
				"jcr:docmd", "baz=xyz", "--clear-env", "--load-env-from-properties-file=src/test/resources/foo.env",
				SimplerCommandRunner.CMD_CAT, "data:,[", "data:,foo%3D", "x-jcr36-env:foo", "data:,, bar%3D", "x-jcr36-env:bar", "data:,, baz%3D", "x-jcr36-env:baz", "data:,]"
			},
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("[foo=abc, bar=xyz, baz=]", out.toString());
	}
	
	public void testPrintEnv() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{
				"jcr:docmd", "--clear-env", "foo=abc", "bar=xyz",
				SimplerCommandRunner.CMD_PRINTENV
			},
			0, ENV_W_ALIASES, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("bar=xyz\nfoo=abc\n", out.toString());
	}
	
	public void testOptionNotParsedAsCommand() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{
				// Without '--', our goofy alias should be ignored, and --clear-env has its normal meaning:
				SimplerCommandRunner.CMD_DOCMD, "--clear-env", SimplerCommandRunner.CMD_PRINT, "foo",
			},
			0, ENV_W_CLEARENV_MEANS_PRINT, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("foo\n", out.toString());
	}
	public void testPostDashDashOptionesqueCommand() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{
				// '--' should force the next arg to be interpreted as a command.
				SimplerCommandRunner.CMD_DOCMD, "--", "--clear-env", SimplerCommandRunner.CMD_PRINT, "foo",
			},
			0, ENV_W_CLEARENV_MEANS_PRINT, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals(SimplerCommandRunner.CMD_PRINT+" foo\n", out.toString());
	}
	public void testJcrDoCmdResetsOptionParsing() {
		OutputCollector out = OutputCollector.create();
		int exitCode = SimplerCommandRunner.doJcrDoCmd(
			new String[]{
				// '--' should force the next arg to be interpreted as a command;
				// if that command is 'jcr:docmd', it should parse the remaining arguments
				// normally (i.e. our recursion -> loop optimization should be transparent,
				// and therefore --clear-env should be treated as an option, not a command).
				SimplerCommandRunner.CMD_DOCMD, "--", SimplerCommandRunner.CMD_DOCMD, "--clear-env", SimplerCommandRunner.CMD_PRINT, "foo",
			},
			0, ENV_W_CLEARENV_MEANS_PRINT, new Object[] { null, out, System.err });
		assertEquals(0, exitCode);
		assertEquals("foo\n", out.toString());
	}
	
	@Override public void run() {
		testMainNothing();
		testPrint();
		testPrintN();
		testExit();
		testExitByLongName();
		testExit123();
		testExitN456();
		testRunSysProc();
		testRunSysProcToNul();
		testRunJcrAsSysProc();
		testCatDataUri();
		testCatDataUri2();
		testCatFile();
		testCatEnvUris();
		testLoadAndCatEnvUris();
		testPrintEnv();
		testOptionNotParsedAsCommand();
		testPostDashDashOptionesqueCommand();
		testJcrDoCmdResetsOptionParsing();
	}
	
	public static void main(String[] args) {
		new SimplerCommandRunnerTest().run();
		System.out.println("All tests passed!");
	}
}
