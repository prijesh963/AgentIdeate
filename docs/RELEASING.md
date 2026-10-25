# Releasing and distributing agentideate

## Cut a release (maintainer)

1. Set the version in `build.gradle.kts` (`version = "0.5.0"`), merge to `main`.
2. Tag that commit with the same version and push the tag:
   ```bash
   git tag v0.5.0 && git push origin v0.5.0
   ```
3. The **Release** workflow checks the tag matches the build version, builds and tests, runs the
   benchmark, and publishes a GitHub Release with:
   - `agentideate.jar`: the whole tool, one file;
   - `agentideate.jar.sha256`: its checksum;
   - `agentideate-sbom.json`: a CycloneDX 1.5 software bill of materials (the bundled libraries).
   The release notes carry the checksum and the benchmark output.

Every push also builds these three files in CI (artifact `agentideate`), so a packaging problem
shows up before anyone tags.

To build them locally: `./gradlew release` (jar and checksum in `build/libs`, SBOM in `build/sbom`).

## Put it in Artifactory (once per release)

Download the three files from the GitHub Release, verify, then upload to a generic repository:

```bash
sha256sum -c agentideate.jar.sha256                        # Linux / macOS / Git Bash: "agentideate.jar: OK"

curl -u "$ARTIFACTORY_USER:$ARTIFACTORY_TOKEN" -T agentideate.jar \
  "https://artifactory.example.com/artifactory/<generic-repo>/agentideate/0.5.0/agentideate.jar"
curl -u "$ARTIFACTORY_USER:$ARTIFACTORY_TOKEN" -T agentideate.jar.sha256 \
  "https://artifactory.example.com/artifactory/<generic-repo>/agentideate/0.5.0/agentideate.jar.sha256"
curl -u "$ARTIFACTORY_USER:$ARTIFACTORY_TOKEN" -T agentideate-sbom.json \
  "https://artifactory.example.com/artifactory/<generic-repo>/agentideate/0.5.0/agentideate-sbom.json"
```

(or `jf rt upload` with the JFrog CLI). Hand the SBOM and `docs/SECURITY.md` to whoever reviews
tools for approval.

## Install (each developer)

1. Download `agentideate.jar` (and `.sha256`) from Artifactory into `~/.agentideate/`.
2. Verify it:
   ```powershell
   # Windows PowerShell: compare with the first word of agentideate.jar.sha256
   (Get-FileHash $HOME\.agentideate\agentideate.jar -Algorithm SHA256).Hash.ToLower()
   ```
   ```bash
   cd ~/.agentideate && sha256sum -c agentideate.jar.sha256
   ```
3. Print the exact configuration for this machine and paste it into Copilot's MCP settings:
   ```bash
   java -jar ~/.agentideate/agentideate.jar setup
   ```
4. Restart IntelliJ.

To update, replace the jar and restart IntelliJ. The index cache is rebuilt automatically when a new
version changes its format.
