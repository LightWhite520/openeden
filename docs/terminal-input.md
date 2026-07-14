# Terminal input

Interactive CLI sessions use JLine's platform-native terminal provider as the
single owner of raw mode, Unicode input, line editing, history, and output.
OpenEden does not maintain a second JNI line editor.

On Windows, the JLine JNI provider consumes Unicode console events directly, so
the packaged launcher supports Chinese input independently of the active `chcp`
value. Cursor movement, insertion, deletion, and supplementary Unicode characters
are handled by JLine's editor rather than by UTF-16 code-unit operations.

Build and launch the supported distribution with:

```powershell
.\gradlew.bat installDist
.\build\install\openeden\bin\openeden.bat
```

`gradlew run` remains a development convenience. Gradle proxies standard streams
through pipes and is not the supported path for interactive terminal behavior.
