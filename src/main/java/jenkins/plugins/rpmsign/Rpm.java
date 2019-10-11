package jenkins.plugins.rpmsign;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class Rpm extends AbstractDescribableImpl<Rpm> {

  private String gpgKeyName;
  private String includes;
  private String cmdlineOpts;
  private boolean resign;

  @DataBoundConstructor
  public Rpm() {
    this.gpgKeyName = "";
    this.includes = "**/**.rpm";
    this.resign = false;
    this.cmdlineOpts = "";
  }

  @Deprecated
  public Rpm(String gpgKeyName, String includes, String cmdlineOpts, boolean resign) {
    this.gpgKeyName = gpgKeyName;
    this.includes = includes;
    this.resign = resign;
    this.cmdlineOpts = cmdlineOpts;
  }

  public String getGpgKeyName() {
    return gpgKeyName;
  }

  public String getIncludes() {
    return includes;
  }

  public String getCmdlineOpts() {
    return cmdlineOpts;
  }

  public boolean isResign() {
    return resign;
  }

  @DataBoundSetter
  public void setGpgKeyName(final String gpgKeyName) {
    this.gpgKeyName = gpgKeyName;
  }

  @DataBoundSetter
  public void setIncludes(final String includes) {
    this.includes = includes;
  }

  @DataBoundSetter
  public void setCmdlineOpts(final String cmdlineOpts) {
    this.cmdlineOpts = cmdlineOpts;
  }

  @DataBoundSetter
  public void setResign(final boolean resign) {
    this.resign = resign;
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Rpm> {

    @Override
    public String getDisplayName() {
      return ""; // unused
    }
  }
}
