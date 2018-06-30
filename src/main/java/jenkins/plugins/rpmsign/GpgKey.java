package jenkins.plugins.rpmsign;

import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;

public class GpgKey implements Serializable {

  static final long serialVersionUID = 42L;

  @Deprecated
  @SuppressWarnings("unused")
  private transient String id;

  private String name;
  private Secret privateKey;
  private Secret passphrase;

  public GpgKey() {
  }

  @DataBoundConstructor
  public GpgKey(String name, Secret privateKey, Secret passphrase) {
    this.name = name;
    this.privateKey = privateKey;
    this.passphrase = passphrase;
  }

  @SuppressWarnings({"unused", "deprecation"})
  @Deprecated
  public String getId() {
    return id;
  }

  public int getUniqueId() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + (privateKey.getPlainText() != null ? privateKey.getPlainText().hashCode() : 0);
    result = 31 * result + (passphrase.getPlainText() != null ? passphrase.getPlainText().hashCode() : 0);
    return result;
  }

  public Secret getPrivateKey() {
    return privateKey;
  }

  public Secret getPassphrase() {
    return passphrase;
  }

  public String getName() {
    return name;
  }
}
