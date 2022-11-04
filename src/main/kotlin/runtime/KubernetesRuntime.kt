package runtime

import com.fkorotkov.kubernetes.*
import helper.OutputCollector
import helper.Shell
import helper.UniqueID
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable

import com.fkorotkov.kubernetes.extensions.*
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.PodResource
import java.util.concurrent.TimeUnit

/**
 * Runs executables as kubernetes pods. Uses the executable's path as the
 * Docker image name.
 * @author Michel Kraemer
 */
class KubernetesRuntime(config: JsonObject) : OtherRuntime() {
    private val additionalDockerEnvironment: List<String> = config.getJsonArray(
        ConfigConstants.RUNTIMES_DOCKER_ENV, JsonArray()).map { it.toString() }
    private val additionalDockerVolumes: List<String> = config.getJsonArray(
        ConfigConstants.RUNTIMES_DOCKER_VOLUMES, JsonArray()).map { it.toString() }
    private val tmpPath: String = config.getString(ConfigConstants.TMP_PATH) ?:
    throw IllegalStateException("Missing configuration item `${ConfigConstants.TMP_PATH}'")

    override fun execute(executable: Executable, outputCollector: OutputCollector) {
        val additionalEnvironment = additionalDockerEnvironment.map {
            Argument(id = UniqueID.next(),
                label = "-e", variable = ArgumentVariable(UniqueID.next(), it),
                type = Argument.Type.INPUT)
        }
        val additionalVolumes = additionalDockerVolumes.map {
            Argument(id = UniqueID.next(),
                label = "-v", variable = ArgumentVariable(UniqueID.next(), it),
                type = Argument.Type.INPUT)
        }

        // keep container name if already defined
        val existingContainerName = executable.runtimeArgs.firstOrNull { it.label == "--name" }?.variable?.value
        val containerName = existingContainerName ?: "steep-${executable.id}-${executable.serviceId}-${UniqueID.next()}"
            .lowercase().replace("""[^a-z0-9]""".toRegex(), "-")
        val containerNameArgument = if (existingContainerName == null) {
            listOf(Argument(id = UniqueID.next(),
                label = "--name", variable = ArgumentVariable("dockerContainerName", containerName),
                type = Argument.Type.INPUT))
        } else {
            emptyList()
        }

        val processedArgs = mutableListOf<String>()
        for (arg in executable.arguments) {
            if (arg.label != null) {
                processedArgs.add(arg.label)
                println("label" + arg.label)
            }
            processedArgs.add(arg.variable.value)
        }

        val id = UniqueID.next()

        val config = ConfigBuilder()
            .withMasterUrl("127.0.0.1:8081")
            .withNamespace("default")
            .withTrustCerts(true)
            .build()
        val client = KubernetesClientBuilder()
            .withConfig(config)
            .build()

        try {

        client.namespaces().resource(newNamespace {
            metadata {
                name = "default"
            }
        }).createOrReplace()

        client.pods().resource(newPod {
            metadata {
                name = "test-pod-" + id
                spec {
                    volumes = listOf(
                        newVolume {
                            name = "task-pv-storage"
                            persistentVolumeClaim = newPersistentVolumeClaimVolumeSource{
                                claimName = "task-pv-claim"
                            }
                        }
                    )
                    containers = listOf(
                        newContainer {
                            name = "custom-container"
                            image = executable.path
                            args = processedArgs
                            volumeMounts = listOf(
                                newVolumeMount {
                                    mountPath = "/C/Users/hanna/Documents/uni/22_sose/thesis/steep"
                                    name = "task-pv-storage"
                                }
                            )
                        }
                    )
                    imagePullSecrets = listOf(
                         newLocalObjectReference {
                            name = "regcred"
                        }
                    )
                    restartPolicy = "OnFailure"
                }
            }
        }).createOrReplace()

        client.pods().inNamespace("default").withField("metadata.name", "test-pod-" + id).waitUntilCondition({pod -> println(pod.status.phase); pod.status.phase.equals("Succeeded")}, 3, TimeUnit.MINUTES)
        println("Success")

        } catch (e: InterruptedException) {
            try {
                //TODO
                Shell.execute(listOf("kubectl", "kill", containerName), outputCollector)
                Shell.execute(listOf("minikube", "stop"), outputCollector)
            } catch (t: Throwable) {
                // ignore
            }
            throw e
        }
    }
}
