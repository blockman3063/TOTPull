# Xposed loads the entry class by name from META-INF/xposed/java_init.list;
# renaming it would silently disable the whole module.
-keep class io.blockman.totpull.MainHook { *; }
