package br.com.healthstaff.backend.extrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** Garante que o servidor falso não serializa requisições (causa de teste intermitente). */
class ExtratorFalsoTest {

	@Test
	void respostaLentaNaoBloqueiaAsDemais() throws Exception {
		try (var extrator = new ExtratorFalso(); var http = HttpClient.newHttpClient()) {
			extrator.atrasar(Duration.ofSeconds(3));
			var requisicao = HttpRequest.newBuilder(URI.create(extrator.url() + "/health")).build();
			CompletableFuture<HttpResponse<String>> lenta = http.sendAsync(requisicao, HttpResponse.BodyHandlers.ofString());
			Thread.sleep(200); // a lenta já está dormindo no servidor

			extrator.atrasar(Duration.ZERO);
			long inicio = System.nanoTime();
			var rapida = http.send(requisicao, HttpResponse.BodyHandlers.ofString());

			assertThat(rapida.statusCode()).isEqualTo(200);
			assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
			assertThat(lenta.get().statusCode()).isEqualTo(200);
		}
	}
}
