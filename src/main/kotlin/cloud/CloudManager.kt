package cloud

import AddressConstants
import ConfigConstants.CLOUD_CREATED_BY_TAG
import ConfigConstants.CLOUD_SETUPS_FILE
import ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION
import ConfigConstants.CLOUD_SSH_USERNAME
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import helper.UniqueID
import helper.YamlUtils
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.shareddata.getAsyncMapAwait
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.removeAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.setup.Setup
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.StringWriter

/**
 * Acquires remote agents on demand. Creates virtual machines, deploys the
 * JobManager to them, and destroys them if they are not needed anymore.
 * @author Michel Kraemer
 */
class CloudManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(CloudManager::class.java)

    /**
     * A metadata key indicating that a virtual machine has been created
     * by the JobManager
     */
    private const val CREATED_BY = "Created-By"

    /**
     * A metadata key indicating which setup a virtual machine has
     */
    private const val SETUP_ID = "Setup-Id"

    /**
     * The name of a map containing IDs of setups we are currently creating a
     * virtual machine for
     */
    private const val CREATING_SETUPS_MAP_NAME = "CloudManager.CreatingSetups"
  }

  /**
   * The client to connect to the Cloud
   */
  private lateinit var cloudClient: CloudClient

  /**
   * A metadata item indicating that a virtual machine has been created
   * by the JobManager
   */
  private lateinit var createdByTag: String

  /**
   * The username for SSH access to created virtual machines
   */
  private lateinit var sshUsername: String

  /**
   * A SSH private key used for authentication when logging in to the new
   * virtual machines
   */
  private lateinit var sshPrivateKeyLocation: String

  /**
   * A list of pre-configured setups
   */
  private lateinit var setups: List<Setup>

  /**
   * A map containing IDs of setups we are currently creating a virtual
   * machine for
   */
  private lateinit var creatingSetups: AsyncMap<String, Boolean>

  override suspend fun start() {
    log.info("Launching cloud manager ...")

    // load configuration
    cloudClient = CloudClientFactory.create(vertx)
    createdByTag = config.getString(CLOUD_CREATED_BY_TAG) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_CREATED_BY_TAG'")

    sshUsername = config.getString(CLOUD_SSH_USERNAME) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_USERNAME'")
    sshPrivateKeyLocation = config.getString(CLOUD_SSH_PRIVATE_KEY_LOCATION) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_PRIVATE_KEY_LOCATION'")

    // load setups file
    val setupsFile = config.getString(CLOUD_SETUPS_FILE) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SETUPS_FILE'")
    setups = YamlUtils.mapper.readValue(File(setupsFile))

    // initialize shared maps
    val sharedData = vertx.sharedData()
    creatingSetups = sharedData.getAsyncMapAwait(CREATING_SETUPS_MAP_NAME)

    // destroy all virtual machines we created before to start from scratch
    val existingVMs = cloudClient.listVMs { createdByTag == it[CREATED_BY] }
    launch {
      for (id in existingVMs) {
        log.info("Found existing VM `$id' ...")
        cloudClient.destroyVM(id)
      }
    }

    // create new virtual machines on demand
    vertx.eventBus().consumer<JsonArray>(AddressConstants.REMOTE_AGENT_MISSING) { msg ->
      val requiredCapabilities = msg.body()
      if (requiredCapabilities != null) {
        launch {
          createRemoteAgent(requiredCapabilities.map { it as String }.toSet())
        }
      }
    }
  }

  /**
   * Select a [Setup] that satisfies the given [requiredCapabilities]. May
   * return `null` if there is no matching [Setup].
   */
  private fun selectSetup(requiredCapabilities: Set<String>): Setup? =
      setups.find { it.providedCapabilities.containsAll(requiredCapabilities) }

  /**
   * Create a virtual machine that matches the given [requiredCapabilities]
   * and deploy a remote agent to it
   */
  internal suspend fun createRemoteAgent(requiredCapabilities: Set<String>) {
    val setup = selectSetup(requiredCapabilities) ?: throw IllegalStateException(
        "Could not find a setup that can satisfy the required capabilities: " +
            requiredCapabilities)

    if (creatingSetups.getAwait(setup.id) == true) {
      // we are already creating a virtual machine with this setup
      return
    }

    log.info("Creating virtual machine with setup `${setup.id}' for " +
        "capabilities $requiredCapabilities ...")

    creatingSetups.putAwait(setup.id, true)
    try {
      val vmId = createVM(setup)
      try {
        val ipAddress = cloudClient.getIPAddress(vmId)
        val agentId = UniqueID.next()
        provisionVM(ipAddress, vmId, agentId, setup)
      } catch (e: Throwable) {
        cloudClient.destroyVM(vmId)
        throw e
      }
    } finally {
      creatingSetups.removeAwait(setup.id)
    }
  }

  /**
   * Create a virtual machine with the given [Setup] and return its ID
   */
  private suspend fun createVM(setup: Setup): String {
    val metadata = mapOf(CREATED_BY to createdByTag, SETUP_ID to setup.id)

    val imageId = cloudClient.getImageID(setup.imageName)
    val blockDeviceId = cloudClient.createBlockDevice(imageId,
        setup.blockDeviceSizeGb, metadata)
    try {
      return cloudClient.createVM(setup.flavor, blockDeviceId, metadata)
    } catch (t: Throwable) {
      cloudClient.destroyBlockDevice(blockDeviceId)
      throw t
    }
  }

  /**
   * Provisions a virtual machine
   * @param ipAddress the VM's IP address
   * @param vmId the VM's ID
   * @param agentId the ID the agent running on the new VM should have
   * @param setup the setup that contains information how to provision the VM
   */
  private suspend fun provisionVM(ipAddress: String, vmId: String,
      agentId: String, setup: Setup) {
    val ssh = SSHClient(ipAddress, sshUsername, sshPrivateKeyLocation, vertx)
    waitForSSH(ipAddress, vmId, ssh)

    // register a handler that waits for the agent on the new virtual machine
    // to become available
    val future = Future.future<Unit>()
    val consumer = vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_AVAILABLE) { msg ->
      if (msg.body() == agentId) {
        future.complete()
      }
    }

    log.info("Provisioning server $ipAddress ...")

    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .build()
    val context = mapOf<String, Any>(
        "config" to config.map,
        "ipAddress" to ipAddress,
        "agentId" to agentId,
        "agentCapabilities" to setup.providedCapabilities
    )

    // run provisioning scripts
    for (script in setup.provisioningScripts) {
      // compile script template
      val compiledTemplate = engine.getTemplate(script)
      val writer = StringWriter()
      compiledTemplate.evaluate(writer, context)

      // upload compiled script
      val destFileName = "/tmp/" + FilenameUtils.getName(script)
      val tmpFile = File.createTempFile("job", null)
      tmpFile.deleteOnExit()
      try {
        tmpFile.writeText(writer.toString())
        ssh.uploadFile(tmpFile.absolutePath, destFileName)
      } finally {
        tmpFile.delete()
      }

      // execute script
      ssh.execute("sudo chmod +x $destFileName")
      ssh.execute("sudo $destFileName")
    }

    // throw if the agent does not become available after a set amount of time
    // TODO make time configurable
    val timeout = 1000 * 60 * 5L
    val timerId = vertx.setTimer(timeout) {
      future.fail("Remote agent `$agentId' on virtual machine `$vmId' with " +
          "IP address `$ipAddress' did not become available after $timeout ms")
    }

    try {
      future.await()
      log.info("Successfully created remote agent `$agentId' on virtual " +
          "machine `$vmId' with IP address `$ipAddress'.")
    } finally {
      consumer.unregister()
      vertx.cancelTimer(timerId)
    }
  }

  /**
   * Wait for an SSH connection to become available
   * @param ipAddress the IP address of the virtual machine to wait for
   * @param vmId the ID of the virtual machine to wait for
   * @param ssh an SSH client
   */
  private suspend fun waitForSSH(ipAddress: String, vmId: String, ssh: SSHClient) {
    val retries = 150
    val retrySeconds = 2

    for (i in 1..retries) {
      cloudClient.waitForVM(vmId)

      log.info("Waiting for SSH: $ipAddress")

      try {
        ssh.tryConnect(retrySeconds)
      } catch (e: IOException) {
        delay(retrySeconds * 1000L)
        continue
      }

      return
    }

    throw IllegalStateException("Too many attempts to connect to SSH")
  }
}
