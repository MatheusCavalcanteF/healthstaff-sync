package br.com.healthstaff.backend.importacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.healthstaff.backend.IntegracaoComBanco;
import br.com.healthstaff.backend.seguranca.Perfil;
import br.com.healthstaff.backend.seguranca.TokenService;
import br.com.healthstaff.backend.seguranca.UsuarioRepository;
import br.com.healthstaff.backend.seguranca.UsuariosDeTeste;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * O limite de upload é aplicado pelo Tomcat (o MockMvc não o reproduz):
 * servidor real, limite de 1 KB.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		"spring.servlet.multipart.max-file-size=1KB",
		"spring.servlet.multipart.max-request-size=1KB" })
class TamanhoUploadTest extends IntegracaoComBanco {

	@LocalServerPort
	int porta;

	@Autowired
	UsuarioRepository usuarios;

	@Autowired
	TokenService tokens;

	String token;

	@BeforeEach
	void autenticar() {
		token = tokens.emitir(UsuariosDeTeste.obter(usuarios, "upload@teste.local", Perfil.GESTOR_RH)).token();
	}

	@ParameterizedTest(name = "{0} bytes")
	@ValueSource(ints = { 2 * 1024, 3 * 1024 * 1024 })
	void arquivoAcimaDoLimiteRecebe413ComCodigo(int tamanho) throws Exception {
		var resposta = enviar(tamanho, "Bearer " + token);

		assertThat(resposta.statusCode()).isEqualTo(413);
		assertThat(resposta.headers().firstValue("Content-Type")).hasValueSatisfying(
				tipo -> assertThat(tipo).startsWith("application/problem+json"));
		assertThat(resposta.body()).contains("\"codigo\":\"ARQUIVO_MUITO_GRANDE\"");
	}

	@Test
	void uploadGrandeSemTokenRecebe401EmVezDeConexaoDerrubada() throws Exception {
		// A segurança recusa antes de ler o corpo; o Tomcat descarta o resto e responde.
		var resposta = enviar(3 * 1024 * 1024, null);

		assertThat(resposta.statusCode()).isEqualTo(401);
		assertThat(resposta.body()).contains("\"codigo\":\"NAO_AUTENTICADO\"");
	}

	private HttpResponse<String> enviar(int tamanho, String autorizacao) throws Exception {
		var fronteira = "----healthstaff";
		var corpo = new ByteArrayOutputStream();
		corpo.write(("--" + fronteira + "\r\n"
				+ "Content-Disposition: form-data; name=\"arquivo\"; filename=\"escala.xlsx\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		var conteudo = new byte[tamanho];
		Arrays.fill(conteudo, (byte) 'x');
		corpo.write(conteudo);
		corpo.write(("\r\n--" + fronteira + "--\r\n").getBytes(StandardCharsets.UTF_8));

		var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/v1/importacoes/escalas"))
				.header("Content-Type", "multipart/form-data; boundary=" + fronteira)
				.POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray()));
		if (autorizacao != null) {
			requisicao.header("Authorization", autorizacao);
		}
		try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			return http.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
		}
	}
}
