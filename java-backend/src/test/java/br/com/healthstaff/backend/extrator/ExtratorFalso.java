package br.com.healthstaff.backend.extrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP real (JDK) no lugar do python-extractor: responde o que o
 * teste mandar e guarda as requisições recebidas para inspeção.
 */
final class ExtratorFalso implements AutoCloseable {

	record Requisicao(String metodo, String caminho, Headers cabecalhos, byte[] corpo) {

		String corpoTexto() {
			return new String(corpo, StandardCharsets.UTF_8);
		}

		String cabecalho(String nome) {
			return cabecalhos.getFirst(nome);
		}
	}

	private final HttpServer servidor;
	private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
	private final List<Requisicao> recebidas = new CopyOnWriteArrayList<>();
	private volatile int status;
	private volatile String corpo;
	private volatile String tipoConteudo;
	private volatile Duration atraso;

	ExtratorFalso() {
		try {
			servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		servidor.createContext("/", this::tratar);
		// Sem executor o HttpServer atende uma requisição por vez: a resposta lenta do
		// teste de timeout seguraria a requisição do teste seguinte.
		servidor.setExecutor(threads);
		servidor.start();
		reiniciar();
	}

	String url() {
		return "http://127.0.0.1:" + servidor.getAddress().getPort();
	}

	void reiniciar() {
		recebidas.clear();
		responder(200, "{}");
		atraso = Duration.ZERO;
	}

	void responder(int status, String corpoJson) {
		responder(status, corpoJson, "application/json");
	}

	void responder(int status, String corpo, String tipoConteudo) {
		this.status = status;
		this.corpo = corpo;
		this.tipoConteudo = tipoConteudo;
	}

	void atrasar(Duration atraso) {
		this.atraso = atraso;
	}

	Requisicao ultima() {
		return recebidas.getLast();
	}

	private void tratar(HttpExchange troca) throws IOException {
		try (troca) {
			byte[] recebido = troca.getRequestBody().readAllBytes();
			var cabecalhos = new Headers();
			cabecalhos.putAll(troca.getRequestHeaders());
			recebidas.add(new Requisicao(troca.getRequestMethod(), troca.getRequestURI().getPath(), cabecalhos, recebido));
			if (!atraso.isZero()) {
				Thread.sleep(atraso);
			}
			byte[] resposta = corpo.getBytes(StandardCharsets.UTF_8);
			troca.getResponseHeaders().set("Content-Type", tipoConteudo);
			troca.sendResponseHeaders(status, resposta.length);
			troca.getResponseBody().write(resposta);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		servidor.stop(0);
		threads.shutdownNow();
	}
}
