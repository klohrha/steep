package runtime

import helper.OutputCollector
import helper.Shell
import helper.UniqueID
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable

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


        val args = executable.arguments.map { argument ->
            Argument(id = UniqueID.next(),
                label = "--" + argument.id, variable = argument.variable,
                type = Argument.Type.INPUT)
        }

        val applyExec = Executable(id = executable.id, path = "kubectl",
            serviceId = executable.serviceId, arguments = applyArgs + executable.arguments)
        val logsExec = Executable(id = executable.id, path = "kubectl",
            serviceId = executable.serviceId, arguments = logsArgs)
        val kubernetesExec = Executable(id = executable.id, path = "kubectl",
            serviceId = executable.serviceId, arguments = kubernetesArgs + executable.arguments)
        try {
            //super.execute(applyExec, outputCollector)
            //super.execute(logsExec, outputCollector)
            println(executable.arguments)
            super.execute(kubernetesExec, outputCollector)
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
