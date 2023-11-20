package net.nuke24.jcr36;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.reflect.Array;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.base64.Base64;

public class SimplerCommandRunner {
	public static final String VERSION = "JCR36.1.29-dev"; // Bump to 36.1.x for 'simpler' version
	
	public static final int EXIT_CODE_PIPING_ERROR = 23; // Previously -1001
	// Borrowing some 'standard Linux exit codes':
	public static final int EXIT_CODE_USAGE_ERROR = 2; // 'Misuse of shell built-in'
	public static final int EXIT_CODE_COMMAND_NOT_FOUND = 127;
	public static final int EXIT_CODE_INVALID_EXIT_ARGUMENT = 128;
	
	public static final String CMD_DOCMD = "http://ns.nuke24.net/JavaCommandRunner36/Action/DoCmd";
	public static final String CMD_PRINTENV   = "http://ns.nuke24.net/JavaCommandRunner36/Action/PrintEnv";
	public static final String CMD_EXIT  = "http://ns.nuke24.net/JavaCommandRunner36/Action/Exit";
	public static final String CMD_PRINT = "http://ns.nuke24.net/JavaCommandRunner36/Action/Print";
	public static final String CMD_CAT   = "http://ns.nuke24.net/JavaCommandRunner36/Action/Cat";
	public static final String CMD_FINDEXE = "http://ns.nuke24.net/JavaCommandRunner36/Action/FindExe";
	public static final String CMD_RUNSYSPROC = "http://ns.nuke24.net/JavaCommandRunner36/Action/RunSysProc";
	
	static final Charset UTF8 = Charset.forName("UTF-8");
	
	// Quote in the conventional C/Java/JSON style.
	// Don't rely on this for passing to other programs!
	public static String quote(String s) {
		return
			"\"" +
			s.replace("\\","\\\\")
			 .replace("\"", "\\\"")
			 .replace("\r","\\r")
			 .replace("\n","\\n")
			 .replace("\t","\\t")
			 .replace(""+(char)0x1b,"\\x1B")
			+"\"";
	}
	
	static int hexDecodeDigit(int dig) {
		if( dig >= '0' && dig <= '9' ) return dig - '0';
		if( dig >= 'a' && dig <= 'f' ) return 10 + dig - 'a';
		if( dig >= 'A' && dig <= 'F' ) return 10 + dig - 'A';
		throw new IllegalArgumentException("Invalid hex digit '"+(char)dig+"'");
	}
	static char hexEncodeDigit(int value) {
		if( value < 0 || value > 15 ) throw new IllegalArgumentException("Value out of range for single-digit hex encoding: "+value);
		if( value < 10 ) return (char)('0' + value);
		return (char)('a' + value);
	}
	
	public static byte[] urlDecode(String s) {
		byte[] encodedBytes = s.getBytes(UTF8);
		byte[] decodedBytes = new byte[encodedBytes.length];
		int j=0;
		for( int i=0; i<encodedBytes.length; ) {
			if( encodedBytes[i] == '%' ) {
				if( encodedBytes.length < i+3 ) throw new IllegalArgumentException("Truncated percent sequence at index "+i+" of "+s);
				decodedBytes[j++] = (byte)((hexDecodeDigit(encodedBytes[i+1]) << 4) | (hexDecodeDigit(encodedBytes[i+2])));
				i += 3;
			} else {
				decodedBytes[j++] = encodedBytes[i++];
			}
		}
		return (j == decodedBytes.length) ?
			decodedBytes :
			Arrays.copyOfRange(decodedBytes, 0, j);
	}
	
	/**
	 * Encode characters as needed for a file: URL;
	 * Specifically leaves '/' and ':' alone.
	 * See https://en.wikipedia.org/wiki/File_URI_scheme
	 */
	public static String urlEncodePath(byte[] path) {
		StringBuilder encoded = new StringBuilder();
		for( int i=0; i<path.length; ++i ) {
			int c = path[i];
			boolean escapeMe;
			switch(c) {
			case '.': case '/': case ';': case ':': case ',':
			case '_': case '~': case '-':
				escapeMe = false;
				break;
			default:
				escapeMe = !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '1' && c <= '9'));
			}
			if( escapeMe ) {
				encoded.append('%').append(hexEncodeDigit((c >> 4)&0xF)).append(hexEncodeDigit(c & 0xF));
			} else {
				encoded.append((char)c);
			}
		}
		return encoded.toString();
	}
	
	public static String debug(Object obj) {
		if( obj == null ) {
			return "null";
		} else if( obj instanceof String ) {
			return quote((String)obj);
		} else if( obj instanceof String[] ) {
			StringBuilder sb = new StringBuilder("[");
			String sep = "";
			for( Object item : (String[])obj ) {
				sb.append(sep).append(debug(item));
				sep = ", ";
			}
			sb.append("]");
			return sb.toString();
		} else {
			return "("+obj.getClass().getName()+")"+obj.toString();
		}
	}
	
	//// Array functions
	
	public static <T> T[] slice(T[] arr, int offset, int length, Class<T> elementClass) {
		assert( length - offset <= arr.length );
		if( offset == 0 ) return arr;
		
		@SuppressWarnings("unchecked")
		T[] newArr = (T[]) Array.newInstance(elementClass, length);
		
		for( int i=0; i<length; ++i ) {
			newArr[i] = arr[offset+i];
		}
		return newArr;
	}
	public static <T> T[] slice(T[] arr, int offset, Class<T> elementClass) {
		return slice(arr, offset, arr.length-offset, elementClass);
	}
	
	protected static <T> T[] cons(T item, T[] list) {
		@SuppressWarnings("unchecked")
		T[] newList = (T[])Array.newInstance(list.getClass().getComponentType(), list.length + 1);
		newList[0] = item;
		for( int i=0; i<list.length; ++i ) newList[i+1] = list[i];
		return newList;
	}
	
	////
	
	// Require scheme to have at least 2 characters
	// so that windows paths like "C:/foo/bar" are unambiguously
	// recognized as NOT being URIs.
	static final Pattern URI_MATCHER = Pattern.compile("^([a-z][a-z0-9+.-]+):(.*)", Pattern.CASE_INSENSITIVE);
	static final Pattern WIN_PATH_MATCHER = Pattern.compile("^([a-z]):(.*)", Pattern.CASE_INSENSITIVE);
	static final Pattern BITPRINT_URN_PATTERN = Pattern.compile("^urn:bitprint:([A-Z2-7]{32})\\.([A-Z2-7]{39})");
	static final Pattern DATA_URI_PATTERN = Pattern.compile("^data:([^,;]*)(;base64)?,(.*)"); // TODO: support the rest of it!
	static final Pattern ENV_URI_PATTERN = Pattern.compile("^x-jcr36-env:(.*)");
	static final Pattern FILE_URI_MATCHER = Pattern.compile("file:(.*)", Pattern.CASE_INSENSITIVE);
	// Resolves to the absolute path of the file named by the rest of the URI.
	// Mostly here for testing.  Is a crappy stand-in for a proper function.
	static final Pattern ABSOLUTE_PATH_URI_PATTERN = Pattern.compile("^x-jcr36-absolute-path:(.*)");
	
	public static String resolveFilePath(File pwd, String path, boolean unc) {
		path = path.replace("\\", "/");
		boolean relativeResolved = false;
		Matcher m;
		while( true ) {
			if( path.startsWith("//") ) {
				return path;
			} else if( path.startsWith("/") ) {
				return unc ? "//" + path : path;
			} else if( (m = WIN_PATH_MATCHER.matcher(path)).matches() ) {
				String winPath = m.group(1).toUpperCase()+":"+m.group(2);
				return unc ? "///" + winPath : winPath;
			} else if( !relativeResolved ) {
				// relative
				path = new File(pwd, path).getAbsolutePath().replace("\\","/");
				relativeResolved = true;
			} else {
				// Warning might be in order here
				return path;
			}
		}
	}
	
	public static List<String> resolveUri(String uri, File pwd, Map<String,String> env) {
		Matcher m;
		if( (m = BITPRINT_URN_PATTERN.matcher(uri)).matches() ) {
			m.group(1);
			throw new RuntimeException("Hash URN resolution not yet supported");
		} else if( (m = FILE_URI_MATCHER.matcher(uri)).matches() ) {
			// Still need to resolve it, in case it's a relative path
			String path = resolveFilePath(pwd, new String(urlDecode(m.group(1)), UTF8), true);
			return Collections.singletonList("file:"+urlEncodePath(path.getBytes(UTF8)));
		} else if( URI_MATCHER.matcher(uri).matches() ) {
			return Collections.singletonList(uri);
		} else {
			String path = resolveFilePath(pwd, uri, true);
			return Collections.singletonList("file:"+urlEncodePath(path.getBytes(UTF8)));
		}
	}
	
	static byte[] base64Decode(byte[] input) {
		return Base64.decode(input);
	}
	
	public static InputStream getInputStream(String name, File pwd, Map<String,String> env) throws IOException {
		List<String> candidates = resolveUri(name, pwd, env);
		for( String uri : candidates ) {
			Matcher m;
			if( (m = DATA_URI_PATTERN.matcher(uri)).matches() ) {
				boolean isBase64 = m.group(2) != null;
				byte[] data = urlDecode(m.group(3));
				data = isBase64 ? base64Decode(data) : data;
				return new ByteArrayInputStream(data);
			} else if( (m = ENV_URI_PATTERN.matcher(uri)).matches() ) {
				String envValue = env.get(m.group(1));
				if( envValue == null ) envValue = "";
				return new ByteArrayInputStream(envValue.getBytes(UTF8));
			} else if( (m = ABSOLUTE_PATH_URI_PATTERN.matcher(uri)).matches() ) {
				return new ByteArrayInputStream(resolveFilePath(pwd, m.group(1), false).getBytes(UTF8));
			} else {
				// But note that Java's URL.getConnection might choke when the
				// path contains escape sequences, so we may need to do
				// our own translation, either 'fixing' the URL or translating
				// to a plain old FileInputStream.
				return new URL(uri).openConnection().getInputStream();
			}
		}
		throw new FileNotFoundException("Couldn't resolve '"+name+"' to a readable resource"); 
	}
	
	protected static List<String> resolvePrograms(String name, Map<String,String> env, PrintStream debugStream) {
		String pathSepRegex = Pattern.quote(File.pathSeparator);
		
		String pathsStr = env.get("PATH");
		if( pathsStr == null ) pathsStr = env.get("Path"); // For Windows compatibility
		if( pathsStr == null ) pathsStr = "";
		String[] pathParts = pathsStr.length() == 0 ? new String[0] : pathsStr.split(pathSepRegex);
		String pathExtStr = env.get("PATHEXT");
		String[] pathExts = pathExtStr == null || pathExtStr.length() == 0 ? new String[] {} : pathExtStr.split(pathSepRegex);
		pathExts = cons("", pathExts);
		if( debugStream != null ) {
			debugStream.println("PATH: "+pathsStr);
			debugStream.println("Path separator: "+File.pathSeparator);
			debugStream.println("Path separator regex: "+pathSepRegex);
			debugStream.print("PATH items: ");
			String sep = "";
			for( String path : pathParts ) {
				debugStream.print(sep+path);
				sep = ", ";
			}
			debugStream.println();
			debugStream.println("PATHEXT: "+pathExtStr);
			debugStream.print("PATHEXT items: ");
			sep = "";
			for( String ext : pathExts ) {
				debugStream.print(sep+ext);
				sep = ", ";
			}
			debugStream.println();
		}
		
		List<String> results = new ArrayList<String>();
		
		for( String path : pathParts ) {
			for( String pathExt : pathExts ) {
				File candidate = new File(path + File.separator + name + pathExt);
				if( debugStream != null ) debugStream.println("Checking for "+candidate.getPath()+"...");
				if( candidate.exists() ) {
					if( debugStream != null ) debugStream.println("Found "+candidate.getPath());
					results.add(candidate.getPath());
				}
			}
		}
		
		return results;
	}
	
	protected static String resolveProgram(String name, Map<String,String> env) {
		List<String> x = resolvePrograms(name, env, null);
		return x.size() == 0 ? name : x.get(0);
	}
	
	public static int doJcrPrintEnv(String[] args, int i, Map<String,String> env, Object[] io) {
		if( args.length > i ) {
			throw new RuntimeException("jcr:printenv takes no arguments");
		}
		PrintStream out = toPrintStream(io[1]);
		ArrayList<String> keys = new ArrayList<String>(env.keySet());
		Collections.sort(keys);
		for( String key : keys ) {
			out.print(key+"="+env.get(key)+"\n");
		}
		return 0;
	}
	
	public static int doJcrExit(String[] args, int i) {
		int code;
		if( args.length == i ) {
			code = 0;
		} else if( args.length == i+1 ) {
			try {
				code = Integer.parseInt(args[i]);
			} catch( NumberFormatException e ) {
				throw new RuntimeException("jcr:exit: Failed to parse '"+args[i]+"' as integer", e);
			}
		} else {
			throw new RuntimeException("Too many arguments to jcr:exit: "+debug(slice(args,i,String.class)));
		}
		return code;
	}
	
	static final Pattern OFS_PAT = Pattern.compile("^--ofs=(.*)$");
	
	public static int doJcrCat(String[] args, int i, File pwd, Map<String,String> env, Object[] io) {
		String ofs = ""; // Output file separator, to be symmetric with print --ofs=whatever
		Matcher m;
		for( ; i<args.length; ++i ) {
			if( (m = OFS_PAT.matcher(args[i])).matches() ) {
				ofs = m.group(1);
			} else if( "--".equals(args[i]) ) {
				++i;
				break;
			} else if( args[i].startsWith("-") ) {
				throw new RuntimeException("Unrecognized argument to jcr:print: "+quote(args[i]));
			} else {
				break;
			}
		}
		PrintStream out = toPrintStream(io[1]);
		if( out == null ) return 0;
		String _sep = "";
		for( ; i<args.length; ++i ) {
			out.print(_sep);
			try {
				new Piper(getInputStream(args[i], pwd, env), true, out, false).run();
			} catch (IOException e) {
				PrintStream err = toPrintStream(io[2]); 
				err.print("Failed to open "+args[i]+": ");
				e.printStackTrace(err);
				return 1;
			}
			_sep = ofs;
		}
		return 0;
	}
	
	public static int doFindExe(String[] args, int i, Map<String,String> env, PrintStream out, PrintStream err) {
		PrintStream debugStream = null;
		for( ; i<args.length; ++i ) {
			if( "-v".equals(args[i]) ) {
				debugStream = err;
			} else if( "--".equals(args[i]) ) {
				++i;
				break;
			} else if( !args[i].startsWith("-") ) {
				break;
			} else {
				err.println("FindExe: Unrecognized option: "+quote(args[i]));
				return EXIT_CODE_USAGE_ERROR;
			}
		}
		for( ; i<args.length; ) {
			for( String path : resolvePrograms(args[i++], env, debugStream) ) {
				out.println(path);
			}
		}
		return 0;
	}
	
	public static int doJcrPrint(String[] args, int i, PrintStream out) {
		String ofs = " "; // Output field separator, i.e. OFS in AWK
		String suffix = "\n";
		Matcher m;
		for( ; i<args.length; ++i ) {
			if( "-n".equals(args[i]) ) {
				suffix = "";
			} else if( (m = OFS_PAT.matcher(args[i])).matches() ) {
				ofs = m.group(1);
			} else if( "--".equals(args[i]) ) {
				++i;
				break;
			} else if( args[i].startsWith("-") ) {
				throw new RuntimeException("Unrecognized argument to jcr:print: "+quote(args[i]));
			} else {
				break;
			}
		}
		if( out == null ) return 0;
		String _sep = "";
		for( ; i<args.length; ++i ) {
			out.print(_sep);
			out.print(args[i]);
			_sep = ofs;
		}
		out.print(suffix);
		return 0;
	}
	
	static class Piper extends Thread {
		protected InputStream in;
		protected OutputStream out;
		protected boolean ownIn, ownOut;
		public ArrayList<Throwable> errors = new ArrayList<Throwable>();
		public Piper(InputStream in, boolean ownIn, OutputStream out, boolean ownOut) {
			this.in = in; this.out = out;
		}
		@Override public void run() {
			try {
				byte[] buf = new byte[16384];
				int z;
				while( (z = in.read(buf)) > 0 ) {
					if( out != null ) out.write(buf, 0, z);
				}
			} catch( Exception e ) {
				this.errors.add(e);
			} finally {
				if( this.ownIn ) try {
					in.close();
				} catch (IOException e) {
					this.errors.add(e);
				}
				
				if( this.ownOut ) try {
					if( out != null ) out.close();
				} catch( Exception e ) {
					this.errors.add(e);
				}
			}
		}
		public static Piper start(InputStream in, boolean ownIn, OutputStream out, boolean ownOut) {
			Piper p = new Piper(in, ownIn, out, ownOut);
			p.start();
			return p;
		}
	}
	
	static InputStream toInputStream(Object is) {
		if( is == null ) {
			return new ByteArrayInputStream(new byte[0]);
		} else if( is instanceof InputStream ) {
			return (InputStream)is;
		} else {
			throw new RuntimeException("Don't know how to turn "+debug(is)+" into InputStream");
		}
	}
	
	static OutputStream totOutputStream(Object os) {
		if( os == null ) return null;
		if( os instanceof OutputStream ) return (OutputStream)os;
		throw new RuntimeException("Don't know how to make OutputStream from "+os);
	}
	
	static PrintStream toPrintStream(Object os) {
		if( os == null ) return null;
		if( os instanceof PrintStream ) return (PrintStream)os;
		if( os instanceof OutputStream ) {
			try {
				return new PrintStream((OutputStream)os, false, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}
		throw new RuntimeException("Don't know how to make PrintStream from "+os);
	}
	
	public static int doSysProc(String[] args, int i, File pwd, Map<String,String> env, Object[] io) {
		String[] resolvedArgs = new String[args.length-i];
		resolvedArgs[0] = resolveProgram(args[i++], env);
		for( int j=1; i<args.length; ++i, ++j ) resolvedArgs[j] = args[i];
		ProcessBuilder pb = new ProcessBuilder(resolvedArgs);
		pb.environment().clear();
		pb.environment().putAll(env);
		pb.directory(pwd);
		pb.redirectInput(io[0] == System.in ? Redirect.INHERIT : Redirect.PIPE);
		pb.redirectOutput(io[1] == System.out ? Redirect.INHERIT : Redirect.PIPE);
		pb.redirectError( io[2] == System.err ? Redirect.INHERIT : Redirect.PIPE);
		Process proc;
		try {
			proc = pb.start();
			ArrayList<Piper> pipers = new ArrayList<Piper>();
			if( pb.redirectInput() == Redirect.PIPE ) pipers.add(Piper.start(toInputStream(io[0]), false, proc.getOutputStream(), true));
			if( pb.redirectOutput() == Redirect.PIPE ) pipers.add(Piper.start(proc.getInputStream(), true, totOutputStream(io[1]), false));
			if( pb.redirectError() == Redirect.PIPE ) pipers.add(Piper.start(proc.getErrorStream(), true, totOutputStream(io[2]), false));
			int exitCode = proc.waitFor();
			
			for( Piper p : pipers ) {
				p.join();
				if( !p.errors.isEmpty() && exitCode == 0 ) exitCode = EXIT_CODE_PIPING_ERROR; 
			}
			PrintStream stdErr = toPrintStream(io[2]);
			if( stdErr != null ) for( Piper p : pipers ) for( Throwable e : p.errors ) {
				stdErr.print("Piping error: "+e+"\n");
			}
			
			return exitCode;
		} catch (IOException e) {
			throw new RuntimeException("Failed to run process "+debug(resolvedArgs)+" (pwd="+pwd+")", e);
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while running process "+debug(resolvedArgs)+" (pwd="+pwd+")", e);
		}
	}
	
	public static String envMangleAlias(String name) {
		return "JCR_ALIAS_"+name.replace(":", "_").toUpperCase();
	}
	
	public static String dealiasCommand(String name, Map<String,String> env) {
		String resolved = env.get(envMangleAlias(name));
		return resolved == null ? name : resolved;
	}
	
	public static Map<String,String> STANDARD_ALIASES = new HashMap<String,String>();
	static {
		STANDARD_ALIASES.put("jcr:cat"     , CMD_CAT       );
		STANDARD_ALIASES.put("jcr:docmd"   , CMD_DOCMD     );
		STANDARD_ALIASES.put("jcr:printenv", CMD_PRINTENV  );
		STANDARD_ALIASES.put("jcr:exit"    , CMD_EXIT      );
		STANDARD_ALIASES.put("jcr:print"   , CMD_PRINT     );
		STANDARD_ALIASES.put("jcr:runsys"  , CMD_RUNSYSPROC);
	}
	
	static Map<String,String> loadEnvFromPropertiesFile(String name, File pwd, Map<String,String> env) throws IOException {
		@SuppressWarnings("resource")
		InputStream is = getInputStream(name, pwd, env);
		try {
			Properties props = new Properties();
			props.load(is);
			HashMap<String,String> newEnv = new HashMap<String,String>(env);
			for( Map.Entry<Object,Object> entry : props.entrySet() ) newEnv.put(entry.getKey().toString(), entry.getValue().toString());
			return newEnv;
		} finally {
			is.close();
		}
	}
	
	protected static String HELP_TEXT =
		"Usage: jcr36 [jcr:docmd] [<opts>] [<k>=<v> ...] [--] <command> [<arg> ...]\n"+
		"\n"+
		"Options:\n"+
		"  --cd=<dir>  ; use <dir> as the pwd for the following command\n"+
		"  --clear-env ; do not inherit environment variables\n"+
		"  --load-env-from-properties-file=<file|uri>\n"+
		"\n"+
		"Commands:\n"+
		"  # Set environment variables and run the specified sub-command:\n"+
		"  jcr:docmd [<k>=<v> ...] <command> [<arg> ...]\n"+
		"  \n"+
		"  # print words, separated by <separator> (defauls: one space);\n"+
		"  # -n to omit otherwise-implicit trailing newline:\n"+
		"  jcr:print [-n] [--ofs=<separator>] [--] [<word> ...]\n"+
		"  \n"+
		"  # Exit with status code:\n"+
		"  jrc:exit [<code>]";
	
	static final Pattern LOAD_ENV_FROM_PROPERTIES_FILE_PATTERN = Pattern.compile("--load-env-from-properties-file=(.*)");
	static final Pattern CD_PATTERN = Pattern.compile("--cd=(.*)");
	
	public static int doJcrDoCmd(String[] args, int i, File pwd, Map<String,String> parentEnv, Object[] io)
	{
		Map<String,String> env = parentEnv;
		boolean allowOpts = true;
		Matcher m;
		for( ; i<args.length; ++i ) {
			if( allowOpts ) {
				int eqidx = args[i].indexOf('=');
				if( "--clear-env".equals(args[i]) ) {
					// That's right; it even clears the standard aliases!
					env = parentEnv = Collections.emptyMap();
					continue;
				} else if( "--".equals(args[i]) ) {
					allowOpts = false;
					continue;
				} else if( "--version".equals(args[i]) ) {
					return doJcrPrint(new String[] { VERSION }, 0, toPrintStream(io[1]));
				} else if( "--help".equals(args[i]) ) {
					return doJcrPrint(new String[] { VERSION, "\n", "\n", HELP_TEXT }, 0, toPrintStream(io[1]));
				} else if( (m = LOAD_ENV_FROM_PROPERTIES_FILE_PATTERN.matcher(args[i])).matches() ) {
					try {
						env = parentEnv = loadEnvFromPropertiesFile(m.group(1), pwd, env);
					} catch( IOException e ) {
						throw new RuntimeException("Error reading from properties file '"+m.group(1)+"'", e);
					}
					continue;
				} else if( (m = CD_PATTERN.matcher(args[i])).matches() ) {
					pwd = new File(resolveFilePath(pwd, m.group(1), false));
					continue;
				} else if( args[i].startsWith("-") ) {
					System.err.println("Unrecognized option: "+quote(args[i]));
					return 1;
				} else if( eqidx >= 1 ) {
					if( env == parentEnv ) env = new HashMap<String,String>(parentEnv);
					env.put(args[i].substring(0,eqidx), args[i].substring(eqidx+1));
					continue;
				}
			}
			
			String cmd = dealiasCommand(args[i], env);
			if( CMD_CAT.equals(cmd) ) {
				return doJcrCat(args, i+1, pwd, env, io);
			} else if( CMD_DOCMD.equals(cmd) ) {
				allowOpts = true;
			} else if( CMD_EXIT.equals(cmd) ) {
				return doJcrExit(args, i+1);
			} else if( CMD_FINDEXE.equals(cmd) ) {
				return doFindExe(args, i+1, env, toPrintStream(io[1]), toPrintStream(io[2]));
			} else if( CMD_PRINT.equals(cmd) ) {
				return doJcrPrint(args, i+1, toPrintStream(io[1]));
			} else if( CMD_PRINTENV.equals(cmd) ) {
				return doJcrPrintEnv(args, i+1, env, io);
			} else if( CMD_RUNSYSPROC.equals(cmd) ) {
				return doSysProc(args, i+1, pwd, env, io);
			} else {
				return doSysProc(args, i, pwd, env, io);
			}
		}
		return 0;
	}
	
	public static Map<String,String> withAliases(Map<String,String> env, Map<String,String> aliases) {
		if( aliases.size() == 0 ) return env;
		env = new HashMap<String,String>(env);
		for( Map.Entry<String,String> ae : aliases.entrySet() ) {
			env.put(envMangleAlias(ae.getKey()), ae.getValue());
		}
		return env;
	}
	
	public static int doJcrDoCmdMain(String[] args, int i, File pwd, Map<String,String> env, Object[] io) {
		int argi = 0;
		boolean loadStdAliases = true;
		if( args.length > argi && "--no-std-aliases".equals(args[argi]) ) {
			loadStdAliases = false;
			++argi;
		}
		env = withAliases(env, loadStdAliases ? STANDARD_ALIASES : Collections.<String,String>emptyMap());
		return doJcrDoCmd(args, argi, pwd, env, io);
	}
	
	public static void main(String[] args) {
		System.exit(doJcrDoCmdMain(args, 0, new File("").getAbsoluteFile(), System.getenv(), new Object[] { System.in, System.out, System.err }));
	}
}
