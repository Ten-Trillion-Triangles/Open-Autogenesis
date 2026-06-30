# mapEditor

Standalone KVision-based map editor for creating and editing game maps.

## Build & Run

Start the development server with hot reload:

```bash
../gradlew :mapEditor:jsBrowserDevelopmentRun
```

Disable hot reload while keeping the dev server:

```bash
KVISION_DISABLE_HOT_RELOAD=true ../gradlew :mapEditor:jsBrowserDevelopmentRun
```

## Update Dependencies

Refresh the Yarn lock file after changing npm dependencies:

```bash
../gradlew :mapEditor:kotlinStoreYarnLock
```
