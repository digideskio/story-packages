package conf

import com.amazonaws.AmazonClientException
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import play.api.Play.current
import play.api.{Logger, Play}

object aws {
  def mandatoryCredentials: AWSCredentialsProvider = credentials.getOrElse(throw new BadConfigurationException("AWS credentials are not configured"))
  val credentials: Option[AWSCredentialsProvider] = {
    val provider = new AWSCredentialsProviderChain(
      new ProfileCredentialsProvider("cmsFronts"),
      new InstanceProfileCredentialsProvider
    )

    // this is a bit of a convoluted way to check whether we actually have credentials.
    // I guess in an ideal world there would be some sort of isConfigued() method...
    try {
      val creds = provider.getCredentials
      Some(provider)
    } catch {
      case ex: AmazonClientException =>
        Logger.error("amazon client exception")

        // We really, really want to ensure that PROD is configured before saying a box is OK
        if (Play.isProd) throw ex
        // this means that on dev machines you only need to configure keys if you are actually going to use them
        None
    }
  }
}
