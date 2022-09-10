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
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.util.concurrent.TimeUnit

/**
 * Runs executables as Docker containers. Uses the executable's path as the
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

        val applyArgs = listOf(
            Argument(id = UniqueID.next(),
                variable = ArgumentVariable("apply", "apply"),
                type = Argument.Type.INPUT)
        ) + executable.runtimeArgs + listOf(
            Argument(id = UniqueID.next(),
                label = "-f", variable = ArgumentVariable("kubernetesYaml", "examples/kubernetes-hello-world.yaml"),
                type = Argument.Type.INPUT),
        )

        val logsArgs = listOf(
            Argument(id = UniqueID.next(),
                variable = ArgumentVariable("logs", "logs"),
                type = Argument.Type.INPUT),
            Argument(id = UniqueID.next(),
                variable = ArgumentVariable("counter", "counter"),
                type = Argument.Type.INPUT)
        )

        val kubernetesArgs = listOf(
            Argument(id = UniqueID.next(),
                variable = ArgumentVariable("run", "run"),
                type = Argument.Type.INPUT),
            Argument(id = UniqueID.next(),
                variable = ArgumentVariable("name", executable.id),
                type = Argument.Type.INPUT),
            Argument(id = UniqueID.next(),
                variable = ArgumentVariable("image", "--image=" + executable.path),
                type = Argument.Type.INPUT),
        )



        var arguments = executable.arguments.map { argument ->
            Argument(id = UniqueID.next(),
                argument.id, variable = argument.variable,
                type = Argument.Type.INPUT)
        }

        val processedArgs = mutableListOf<String>()
        for (arg in executable.arguments) {
            if (arg.label != null) {
                processedArgs.add(arg.label)
                println("label" + arg.label)
            }
            processedArgs.add(arg.variable.value)
        }

        println(processedArgs)

        val id = UniqueID.next()

        val config = ConfigBuilder()
            .withMasterUrl("127.0.0.1:8081")
            .withNamespace("default")
            .withTrustCerts(true)
            .build()
        val client = KubernetesClientBuilder()
            .withConfig(config)
            .build()

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
                }
            }
        }).createOrReplace()

        var podList : PodList = client.pods().inNamespace("default").withField("metadata.name", "test-pod-" + id).list()

        client.pods().inNamespace("default").withName(podList.items.get(0).metadata.name)
           .waitUntilCondition({pod -> pod.getStatus().getPhase().equals("Running")}, 5, TimeUnit.MINUTES)
        //println("Pods:" + client.pods().inAnyNamespace().list().items.joinToString("\n") { it.metadata.name })
        println("Arguments: " + client.pods().inNamespace("default").withField("metadata.name", "test-pod-" + id).list().items.joinToString("\n") {it. metadata.name })
        println("Success")

        val applyExec = Executable(id = executable.id, path = "kubectl",
            serviceId = executable.serviceId, arguments = applyArgs + executable.arguments)
        val logsExec = Executable(id = executable.id, path = "kubectl",
            serviceId = executable.serviceId, arguments = logsArgs)
        val kubernetesExec = Executable(id = executable.id, path = "kubectl",
            serviceId = executable.serviceId, arguments = kubernetesArgs + executable.arguments)
        try {
            //super.execute(applyExec, outputCollector)
            //super.execute(logsExec, outputCollector)
            //println(executable.arguments)
            //super.execute(kubernetesExec, outputCollector)
        } catch (e: InterruptedException) {
            try {
                Shell.execute(listOf("kubectl", "kill", containerName), outputCollector)
                Shell.execute(listOf("minikube", "stop"), outputCollector)
            } catch (t: Throwable) {
                // ignore
            }
            throw e
        }
    }
}
