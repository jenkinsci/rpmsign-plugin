package jenkins.plugins.rpmsign;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

public class RpmSignPlugin extends Recorder {

  private List<Rpm> entries = Collections.emptyList();

  @DataBoundConstructor
  public RpmSignPlugin(List<Rpm> rpms) {
    this.entries = rpms;
    if (this.entries == null) {
      this.entries = Collections.emptyList();
    }
  }

  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  private boolean isPerformDeployment(AbstractBuild build) {
    Result result = build.getResult();
    if (result == null) {
      return true;
    }

    return build.getResult().isBetterOrEqualTo(Result.UNSTABLE);
  }

  @SuppressWarnings("unused")
  public List<Rpm> getEntries() {
    return entries;
  }

  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
    if (isPerformDeployment(build)) {
      listener.getLogger().println("[RpmSignPlugin] - Starting signing RPMs ...");

      for (Rpm rpmEntry : entries) {
        StringTokenizer rpmGlobTokenizer = new StringTokenizer(rpmEntry.getIncludes(), ",");

        GpgKey gpgKey = getGpgKey(rpmEntry.getGpgKeyName());
        if (gpgKey != null) {
          listener.getLogger().println("[RpmSignPlugin] - Importing private key");
          importGpgKey(gpgKey.getPrivateKey(), build, launcher, listener);
          listener.getLogger().println("[RpmSignPlugin] - Imported private key");
        } else {
          listener.getLogger().println("[RpmSignPlugin] - Can't find GPG key: " + rpmEntry.getGpgKeyName());
          return false;
        }
        
        if (!isGpgKeyAvailable(gpgKey.getPrivateKey(), build, launcher, listener)){
          listener.getLogger().println("[RpmSignPlugin] - Can't find GPG key: " + rpmEntry.getGpgKeyName());
          return false;
        }

        while (rpmGlobTokenizer.hasMoreTokens()) {
          String rpmGlob = rpmGlobTokenizer.nextToken();

          listener.getLogger().println("[RpmSignPlugin] - Publishing " + rpmGlob);

          FilePath[] matchedRpms = build.getWorkspace().list(rpmGlob);
          if (ArrayUtils.isEmpty(matchedRpms)) {
            listener.getLogger().println("[RpmSignPlugin] - No RPMs matching " + rpmGlob);
          } else {
            ArgumentListBuilder rpmSignCommand = new ArgumentListBuilder();

            rpmSignCommand.add("rpm", "--define");
            rpmSignCommand.add("_gpg_name " + gpgKey.getName());

            if (rpmEntry.isResign()) {
              rpmSignCommand.add("--resign");
            } else {
              rpmSignCommand.add("--addsign");
            }

            for (FilePath rpmFilePath : matchedRpms) {
              rpmSignCommand.add(rpmFilePath.toURI().normalize().getPath());
            }

            String rpmCommandLine = rpmSignCommand.toString();
            listener.getLogger().println("[RpmSignPlugin] - Running " + rpmCommandLine);

            ArgumentListBuilder expectCommand = new ArgumentListBuilder();
            expectCommand.add("expect", "-");

            Launcher.ProcStarter ps = launcher.new ProcStarter();
            ps = ps.cmds(expectCommand).stdout(listener);
            ps = ps.pwd(build.getWorkspace()).envs(build.getEnvironment(listener));

            byte[] expectScript = createExpectScriptFile(rpmCommandLine, gpgKey.getPassphrase().getPlainText());
            ByteArrayInputStream is = new ByteArrayInputStream(expectScript);
            ps.stdin(is);

            Proc proc = launcher.launch(ps);
            int retcode = proc.join();
            if (retcode != 0) {
              listener.getLogger().println("[RpmSignPlugin] - Failed signing RPMs ...");
              return false;
            }
          }
        }
      }

      listener.getLogger().println("[RpmSignPlugin] - Finished signing RPMs ...");
    } else {
      listener.getLogger().println("[RpmSignPlugin] - Skipping signing RPMs ...");
    }
    return true;
  }

  private byte[] createExpectScriptFile(String signCommand, String passphrase)
      throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream(512);

    final PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos));
    try {
      writer.print("spawn ");
      writer.println(signCommand);
      writer.println("expect \"Enter pass phrase: \"");
      writer.print("send -- \"");
      writer.print(passphrase);
      writer.println("\r\"");
      writer.println("expect eof");
      writer.println();

      writer.flush();
    } finally {
      writer.close();
    }

    return baos.toByteArray();
  }

  private void importGpgKey(String privateKey, AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
    ArgumentListBuilder command = new ArgumentListBuilder();
    command.add("gpg", "--import", "-");
    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps = ps.cmds(command).stdout(listener);
    ps = ps.pwd(build.getWorkspace()).envs(build.getEnvironment(listener));

    InputStream is = new ByteArrayInputStream(privateKey.getBytes());

    ps.stdin(is);
    Proc proc = launcher.launch(ps);
    proc.join();
    is.close();
  }
  
  private boolean isGpgKeyAvailable(String privateKey, AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
    ArgumentListBuilder command = new ArgumentListBuilder();
    command.add("gpg", "--fingerprint", privateKey);
    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps = ps.cmds(command).stdout(listener);
    ps = ps.pwd(build.getWorkspace()).envs(build.getEnvironment(listener));
    InputStream is = new ByteArrayInputStream(privateKey.getBytes());

    ps.stdin(is);
    Proc proc = launcher.launch(ps);
    int retcode = proc.join();
    is.close();
    
    return retcode == 0;
  }

  private GpgKey getGpgKey(String gpgKeyName) {
    GpgSignerDescriptor gpgSignerDescriptor = Jenkins.getInstance().getDescriptorByType(GpgSignerDescriptor.class);
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
  @SuppressWarnings("unused")
  public static final class GpgSignerDescriptor extends BuildStepDescriptor<Publisher> {

    public static final String DISPLAY_NAME = Messages.job_displayName();

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    private volatile List<GpgKey> gpgKeys = new ArrayList<GpgKey>();

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
      if (project.getSomeWorkspace() != null) {
        String msg = project.getSomeWorkspace().validateAntFileMask(value);
        if (msg != null) {
          return FormValidation.error(msg);
        }
        return FormValidation.ok();
      } else {
        return FormValidation.warning(Messages.noworkspace());
      }
    }

  }
}