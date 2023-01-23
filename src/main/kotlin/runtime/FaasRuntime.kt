package runtime

import com.fasterxml.jackson.databind.ObjectMapper
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
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.PodResource
import java.util.concurrent.TimeUnit

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Runs executables as Docker containers. Uses the executable's path as the
 * Docker image name.
 * @author Michel Kraemer
 */
class FaasRuntime(config: JsonObject) : OtherRuntime() {

    private val tmpPath: String = config.getString(ConfigConstants.TMP_PATH) ?:
    throw IllegalStateException("Missing configuration item `${ConfigConstants.TMP_PATH}'")
    override fun execute(executable: Executable, outputCollector: OutputCollector) {
        val start = System.currentTimeMillis()
        println("Starting faas execution at: " + start)

        // keep container name if already defined
        val existingContainerName = executable.runtimeArgs.firstOrNull { it.label == "--name" }?.variable?.value
        val containerName = existingContainerName ?: "steep-${executable.id}-${executable.serviceId}-${UniqueID.next()}"
            .lowercase().replace("""[^a-z0-9]""".toRegex(), "-")

        val values = mutableMapOf<String?, String>()
        for (arg in executable.arguments) {
            // temp workaround for s3 writing
            //if (arg.label == "output") {
             //   values[arg.label] = "/tmp"
            //} else {
                values[arg.label] = arg.variable.value
            //}
        }

        val id = UniqueID.next()

        try {
           // print(values)
        //val values = mapOf("input" to "/images", "output" to "/tmp", "s3endpoint" to "http://192.168.178.79:9000/", "s3profile" to "default", "s3bucket" to "myfirstbucket")

        val objectMapper = ObjectMapper()
        val requestBody: String = objectMapper
                .writeValueAsString(values)

        val client = HttpClient.newBuilder().build();
        val request = HttpRequest.newBuilder()
                .uri(URI.create(executable.path))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        println(response.body())

        //client.pods().inNamespace("default").withField("metadata.name", "test-pod-" + id).waitUntilCondition({pod -> println(pod.status.phase); pod.status.phase.equals("Succeeded")}, 3, TimeUnit.MINUTES)
        println("Success")

        } catch (e: InterruptedException) {
            try {
                Shell.execute(listOf("kubectl", "kill", containerName), outputCollector)
                Shell.execute(listOf("minikube", "stop"), outputCollector)
            } catch (t: Throwable) {
                // ignore
            }
            throw e
        }
        println(executable.id + " finished faas execution after: " + (System.currentTimeMillis()-start))
    }
}
