package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CriacaoAdminInicialTest {

	final UsuarioRepository usuarios = mock(UsuarioRepository.class);
	final BCryptPasswordEncoder senhas = new BCryptPasswordEncoder(4);

	CriacaoAdminInicial criacao(String email, String senha) {
		var propriedades = new SegurancaProperties(
				new SegurancaProperties.Jwt(TokenServiceTest.SEGREDO, Duration.ofHours(8)),
				new SegurancaProperties.AdminInicial("Administração", email, senha),
				new SegurancaProperties.Login(5, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(1)));
		return new CriacaoAdminInicial(usuarios, senhas, propriedades);
	}

	@Test
	void criaOPrimeiroAdminComSenhaEmBCrypt() {
		when(usuarios.findByEmail("admin@hospital.local")).thenReturn(Optional.empty());

		criacao(" Admin@Hospital.local ", "senha-forte-123").run(null);

		var salvo = ArgumentCaptor.forClass(Usuario.class);
		verify(usuarios).save(salvo.capture());
		assertThat(salvo.getValue().getEmail()).isEqualTo("admin@hospital.local");
		assertThat(salvo.getValue().getPerfil()).isEqualTo(Perfil.ADMIN);
		assertThat(salvo.getValue().isAtivo()).isTrue();
		assertThat(salvo.getValue().getPassword()).startsWith("$2").isNotEqualTo("senha-forte-123");
		assertThat(senhas.matches("senha-forte-123", salvo.getValue().getPassword())).isTrue();
	}

	@Test
	void comAdminAtivoNaoFazNadaNemValidaAsVariaveis() {
		when(usuarios.existsByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(true);

		criacao("admin@hospital.local", "curta").run(null);

		verify(usuarios, never()).save(any());
	}

	@Test
	void semVariaveisSoAvisa() {
		criacao("", null).run(null);

		verify(usuarios, never()).save(any());
	}

	@Test
	void senhaCurtaImpedeASubida() {
		assertThatThrownBy(() -> criacao("admin@hospital.local", "curta").run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("HEALTHSTAFF_ADMIN_SENHA");
		verify(usuarios, never()).save(any());
	}

	@Test
	void emailInvalidoImpedeASubida() {
		assertThatThrownBy(() -> criacao("admin", "senha-forte-123").run(null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void contaExistenteComOutroPerfilNaoEAlterada() {
		var existente = Usuario.novo("Auditor", "admin@hospital.local", "hash", Perfil.AUDITOR);
		when(usuarios.findByEmail("admin@hospital.local")).thenReturn(Optional.of(existente));

		criacao("admin@hospital.local", "senha-forte-123").run(null);

		verify(usuarios, never()).save(any());
		assertThat(existente.getPerfil()).isEqualTo(Perfil.AUDITOR);
	}
}
