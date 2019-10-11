package jenkins.plugins.rpmsign;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import javax.annotation.Nonnull;

public class RpmSignPlugin extends Recorder implements SimpleBuildStep {

  private List<Rpm> rpms;

  @DataBoundConstructor
  @SuppressWarnings("unused")
  public RpmSignPlugin(List<Rpm> rpms) {
    setRpms(rpms);
  }

  @DataBoundSetter
  public void setRpms(final List<Rpm> rpms) {
    this.rpms = rpms;
    if (this.rpms == null) {
      this.rpms = Collections.emptyList();
    }
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  private boolean isPerformDeployment(final Run<?, ?> build) {
    Result result = build.getResult();
    return result == null || result.isBetterOrEqualTo(Result.UNSTABLE);

  }

  @SuppressWarnings("unused")
  public List<Rpm> getRpms() {
    return rpms;
  }

  @Override
  public void perform(@Nonnull final Run<?, ?> build, @Nonnull final FilePath workspace, @Nonnull final Launcher launcher, @Nonnull final TaskListener listener) throws InterruptedException, IOException {
    if (isPerformDeployment(build)) {
      listener.getLogger().println("[RpmSignPlugin] - Starting signing RPMs ...");

      for (Rpm rpmEntry : rpms) {
        StringTokenizer rpmGlobTokenizer = new StringTokenizer(rpmEntry.getIncludes(), ",");

        GpgKey gpgKey = getGpgKey(rpmEntry.getGpgKeyName());
        if (gpgKey == null) {
          throw new AbortException("No GPG key is available.");
        }
        if (gpgKey.getPrivateKey().getPlainText().length() > 0) {
          listener.getLogger().println("[RpmSignPlugin] - Importing private key");
          importGpgKey(gpgKey.getPrivateKey().getPlainText(), build, workspace, launcher, listener);
          listener.getLogger().println("[RpmSignPlugin] - Imported private key");
        }

        if (!isGpgKeyAvailable(gpgKey, build, workspace, launcher, listener)) {
          listener.getLogger().println("[RpmSignPlugin] - Can't find GPG key: " + rpmEntry.getGpgKeyName());
          throw new AbortException("Can't find GPG key: " + rpmEntry.getGpgKeyName());
        }

        while (rpmGlobTokenizer.hasMoreTokens()) {
          String rpmGlob = rpmGlobTokenizer.nextToken();

          listener.getLogger().println("[RpmSignPlugin] - Publishing " + rpmGlob);

          FilePath[] matchedRpms = workspace.list(rpmGlob);
          if (ArrayUtils.isEmpty(matchedRpms)) {
            listener.getLogger().println("[RpmSignPlugin] - No RPMs matching " + rpmGlob);
          } else {

            int i = 1;
            for (FilePath rpm : matchedRpms) {
              String logPrefix = "[RpmSignPlugin] [" + i + "/" + matchedRpms.length + "] - ";

              String rpmCommandLine = buildRpmSignCmd(rpm, rpmEntry, gpgKey);
              listener.getLogger().println(logPrefix + "Running " + rpmCommandLine);

              ArgumentListBuilder expectCommand = new ArgumentListBuilder();
              expectCommand.add("expect", "-");

              Launcher.ProcStarter ps = launcher.new ProcStarter();
              ps = ps.cmds(expectCommand).stdout(listener);
              ps = ps.pwd(workspace).envs(build.getEnvironment(listener));

              byte[] expectScript = createExpectScriptFile(rpmCommandLine, gpgKey.getPassphrase().getPlainText());
              ByteArrayInputStream is = new ByteArrayInputStream(expectScript);
              ps.stdin(is);

              Proc proc = launcher.launch(ps);
              int returnCode = proc.join();
              if (returnCode != 0) {
                listener.getLogger().println(logPrefix + "Failed signing RPM ...");
                throw new AbortException("Failed signing RPM. returnCode: " + returnCode);
              }
              i++;
            }
          }
        }
      }

      listener.getLogger().println("[RpmSignPlugin] - Finished signing RPMs ...");
    } else {
      listener.getLogger().println("[RpmSignPlugin] - Skipping signing RPMs ...");
    }
  }

  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
    FilePath workspace = build.getWorkspace();
    if (workspace == null) {
      throw new AbortException("Could not get a workspace.");
    }
    perform(build, workspace, launcher, listener);
    return true;
  }

  private String buildRpmSignCmd(FilePath rpmFile, Rpm rpmEntry, GpgKey gpgKey) throws IOException, InterruptedException {
    ArgumentListBuilder rpmSignCommand = new ArgumentListBuilder();

    rpmSignCommand.add("rpm", "--define");
    rpmSignCommand.add("_gpg_name " + gpgKey.getName());
    rpmSignCommand.addTokenized(rpmEntry.getCmdlineOpts());

    if (rpmEntry.isResign()) {
      rpmSignCommand.add("--resign");
    } else {
      rpmSignCommand.add("--addsign");
    }

    rpmSignCommand.add(rpmFile.toURI().normalize().getPath());

    return rpmSignCommand.toString();
  }

  private byte[] createExpectScriptFile(String signCommand, String passphrase)
          throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream(512);

    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
      writer.print("spawn ");
      writer.println(signCommand);
      writer.println("expect {");
      writer.print("-re \"Enter pass *phrase: *\" { log_user 0; send -- \"");
      writer.print(passphrase);
      writer.println("\r\"; log_user 1; }");
      writer.println("eof { catch wait rc; exit [lindex $rc 3]; }");
      writer.println("timeout { close; exit; }");
      writer.println("}");
      writer.println("expect {");
      writer.println("eof { catch wait rc; exit [lindex $rc 3]; }");
      writer.println("timeout close");
      writer.println("}");
      writer.println();

      writer.flush();
    }

    return baos.toByteArray();
  }

  private void importGpgKey(String privateKey, Run<?, ?> build, final FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
    ArgumentListBuilder command = new ArgumentListBuilder();
    command.add("gpg", "--import", "-");
    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps = ps.cmds(command).stdout(listener);
    ps = ps.pwd(workspace).envs(build.getEnvironment(listener));

    try (InputStream is = new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8))) {
      ps.stdin(is);
      Proc proc = launcher.launch(ps);
      proc.join();
    }
  }

  private boolean isGpgKeyAvailable(GpgKey gpgKey, Run<?, ?> build, final FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
    ArgumentListBuilder command = new ArgumentListBuilder();
    command.add("gpg", "--fingerprint", gpgKey.getName());
    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps = ps.cmds(command).stdout(listener);
    ps = ps.pwd(workspace).envs(build.getEnvironment(listener));
    Proc proc = launcher.launch(ps);

    return proc.join() == 0;
  }

  private GpgKey getGpgKey(String gpgKeyName) {
    Jenkins jenkins = Jenkins.get();
    if (jenkins == null) {
      throw new IllegalStateException("Could not get a Jenkins instance.");
    }
    GpgSignerDescriptor gpgSignerDescriptor = jenkins.getDescriptorByType(GpgSignerDescriptor.class);
    if (!StringUtils.isEmpty(gpgKeyName) && !gpgSignerDescriptor.getGpgKeys().isEmpty()) {
      for (GpgKey gpgKey : gpgSignerDescriptor.getGpgKeys()) {
        if (StringUtils.equals(gpgKeyName, gpgKey.getName())) {
          return gpgKey;
        }
      }
    }
    return null;
  }

  @Extension
  @Symbol("rpmSign")
  @SuppressWarnings("unused")
  public static final class GpgSignerDescriptor extends BuildStepDescriptor<Publisher> {

    public static final String DISPLAY_NAME = Messages.job_displayName();

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    private volatile List<GpgKey> gpgKeys = new ArrayList<>();

    public GpgSignerDescriptor() {
      load();
    }

    @Override
    public String getDisplayName() {
      return DISPLAY_NAME;
    }

    public List<GpgKey> getGpgKeys() {
      return gpgKeys;
    }

    public ListBoxModel doFillGpgKeyNameItems() {
      ListBoxModel items = new ListBoxModel();
      for (GpgKey gpgKey : gpgKeys) {
        items.add(gpgKey.getName(), gpgKey.getName());
      }
      return items;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
      gpgKeys = req.bindJSONToList(GpgKey.class, json.get("gpgKey"));
      save();
      return true;
    }

    public FormValidation doCheckName(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
      return FormValidation.validateRequired(value);
    }

    public FormValidation doCheckPrivateKey(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
      return FormValidation.validateRequired(value);
    }

    public FormValidation doCheckPassphrase(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
      return FormValidation.validateRequired(value);
    }

    public FormValidation doCheckIncludes(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException, InterruptedException {
        return FormValidation.ok();
    }

  }
}
