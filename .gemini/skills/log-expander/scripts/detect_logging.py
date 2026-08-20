import os
import re

def detect_logging():
    logging_patterns = {
        "Autogenesis": {
            "import": r"import org\.ttt\.autogenesis\.logging\.Logger",
            "category_import": r"import org\.ttt\.autogenesis\.logging\.LogCategory",
            "usage": r"Logger\.(debug|info|warn|error)\(LogCategory\.",
            "template": "Logger.{level}(LogCategory.{category}, \"{message}\")"
        },
        "SLF4J": {
            "import": r"import org\.slf4j\.Logger",
            "usage": r"log\.(debug|info|warn|error)\(",
            "template": "log.{level}(\"{message}\")"
        },
        "Logback": {
            "import": r"import ch\.qos\.logback\.classic\.Logger",
            "usage": r"logger\.(debug|info|warn|error)\(",
            "template": "logger.{level}(\"{message}\")"
        },
        "Standard": {
            "import": r"import java\.util\.logging\.Logger",
            "usage": r"logger\.log\(Level\.",
            "template": "logger.log(Level.{level}, \"{message}\")"
        }
    }

    results = {}
    
    # Search in a few files to detect the pattern
    for root, dirs, files in os.walk('.'):
        if 'node_modules' in dirs: dirs.remove('node_modules')
        if '.git' in dirs: dirs.remove('.git')
        if 'build' in dirs: dirs.remove('build')
        
        for file in files:
            if file.endswith(('.kt', '.java', '.ts', '.js')):
                path = os.path.join(root, file)
                try:
                    with open(path, 'r') as f:
                        content = f.read()
                        for name, patterns in logging_patterns.items():
                            if re.search(patterns['import'], content):
                                results[name] = results.get(name, 0) + 1
                except:
                    continue
        if len(results) > 0 and sum(results.values()) > 10:
            break

    if not results:
        print("No logging system detected.")
        return

    detected = max(results, key=results.get)
    print(f"Detected logging system: {detected}")
    print(f"Details: {logging_patterns[detected]}")

if __name__ == "__main__":
    detect_logging()