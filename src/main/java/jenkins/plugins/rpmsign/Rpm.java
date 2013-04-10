package jenkins.plugins.rpmsign;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class Rpm extends AbstractDescribableImpl<Rpm> {
  private String gpgKeyName;
  private String includes;
  private boolean resign;

  @DataBoundConstructor
  public Rpm(String gpgKeyName, String includes, boolean resign) {
    this.gpgKeyName = gpgKeyName;
    this.includes = includes;
    this.resign = resign;
  }

  public String getGpgKeyName() {
    return gpgKeyName;
  }

  public String getIncludes() {
    return includes;
  }

  public boolean isResign() {
    return resign;
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Rpm> {
    @Override
    public String getDisplayName() {
      return ""; // unused
    }
  }
}
