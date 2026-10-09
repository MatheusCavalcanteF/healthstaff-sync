package br.com.healthstaff.backend.seguranca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Cria o primeiro administrador na subida, a partir de HEALTHSTAFF_ADMIN_EMAIL e
 * HEALTHSTAFF_ADMIN_SENHA, só quando não existe nenhum ADMIN ativo. Assim
 * nenhuma senha fica no repositório (nem em migração) e cada ambiente define a
 * sua. Depois de criado, as variáveis são ignoradas: mudar a senha no .env não
 * altera o usuário existente.
 */
@Component
class CriacaoAdminInicial implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CriacaoAdminInicial.class);

	private final UsuarioRepository usuarios;
	private final PasswordEncoder senhas;
	private final SegurancaProperties.AdminInicial admin;

	CriacaoAdminInicial(UsuarioRepository usuarios, PasswordEncoder senhas, SegurancaProperties propriedades) {
		this.usuarios = usuarios;
		this.senhas = senhas;
		this.admin = propriedades.adminInicial();
	}

	@Override
	public void run(ApplicationArguments argumentos) {
		if (usuarios.existsByPerfilAndAtivoTrue(Perfil.ADMIN)) {
			return;
		}
		if (!StringUtils.hasText(admin.email()) || !StringUtils.hasText(admin.senha())) {
			log.warn("nenhum administrador ativo: defina HEALTHSTAFF_ADMIN_EMAIL e HEALTHSTAFF_ADMIN_SENHA "
					+ "e reinicie o backend para criar o primeiro");
			return;
		}
		if (!admin.email().contains("@")) {
			throw new IllegalStateException("HEALTHSTAFF_ADMIN_EMAIL inválido");
		}
		var senha = admin.senha();
		PoliticaSenha.problema(senha).ifPresent(problema -> {
			throw new IllegalStateException("HEALTHSTAFF_ADMIN_SENHA inválida: " + problema);
		});
		var email = Usuario.normalizarEmail(admin.email());
		if (usuarios.findByEmail(email).isPresent()) {
			// Conta existente inativa ou com outro perfil: decisão de quem administra, não da subida.
			log.warn("nenhum administrador ativo, mas {} já existe com outro perfil ou inativo; nada foi alterado",
					email);
			return;
		}
		usuarios.save(Usuario.novo(admin.nome(), email, senhas.encode(senha), Perfil.ADMIN));
		log.info("administrador inicial criado: {}", email);
	}
}
