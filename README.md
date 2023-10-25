# JavaCommandrunner36

A small, retro-ish (runs on JDK 1.6) Java program for running commands
to spawn processes and other suchlike in a more platform-agnostic way
than $SHELL allows.

Command syntax:

```
jcr:docmd [<option> ...] [<var>=<value> ...] [--] <program> [<arg> ...]
```

When run on the command-line, `jcr:docmd` is implied.

## Conceptual Model

In a command like `jcr:print foo bar`, `jcr:print` names a `JCRProcedure`,
while the whole string represents a `JCRCommand`.

- A `JCRProcedure` can be thought of as a function of
  the remaining arguments which returns a `JCRCommand`
- A `JCRCommand` can be thought of as a function of the
  mapping of environment variable names to values
  which returns a `JCRAction`
- A `JCRAction` describes a procedure to be run,
  including information on how its IO streams should be configured
  based on the environment.
- A `JCRProcess` is the instantiation of a `JCRAction`.
  While running, a JCRProcess can read and write from its IO
  streams and otherwise interact with the environment.
  When it finishes, it returns an int32 exit code.
  Each `JCRProcess` runs exactly once.

In other words:
```
# Mapping command strings to commands can be throught of as a purely
# functional operation:
JCRProceduire = (argv : List<String>) -> JCRCommand
JCRCommand    = (environment : Map<String,String>) -> JCRAction

# Instantiating and running a process are not pure functions:
startProcess(action : JCRAction, io : List<IO>) -> JCRProcess
waitForProcess(process : JCRProcess) -> Int32
```

To keep things simple, the current implementation does not separate these steps
(as of v36.1.24, everything from parsing the command to waiting for the process
to complete is done at once by `SimplerCommandRunner#doJcrDoCmd` et al),
but the conceptual model is documented here for the sake of alternative
methods of constructing commands and actions.

For starters, it may be reasonable to allow some procedures to take
more structured arguments, e.g. so that sub-commands can be unambiguously
represented.

## Commands / 'action constructors'

|  Default    |                                                         |
|   alias     |  Full name                                              |
|-------------|---------------------------------------------------------|
| `jcr:docmd` | `http://ns.nuke24.net/JavaCommandRunner36/Action/DoCmd` |
| `jcr:exit`  | `http://ns.nuke24.net/JavaCommandRunner36/Action/Exit`  |
| `jcr:print` | `http://ns.nuke24.net/JavaCommandRunner36/Action/Print` |
| `jcr:runsys` | `http://ns.nuke24.net/JavaCommandRunner36/Action/RunSysProc` |
