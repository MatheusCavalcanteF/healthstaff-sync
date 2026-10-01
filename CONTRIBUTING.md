# HealthStaff Sync - Conciliador de Escalas e Banco de Horas

## Contexto do Projeto
Sistema de gestão hospitalar para conciliar escalas manuais enviadas em PDF pelos gestores com os registros do sistema de ponto (Two em formato CSV).

## Problemas
- Funcionários com saldo de horas altamente positivo sendo escalados para plantões extras.
- Funcionários com saldo negativo ficando de folga.
- Riscos trabalhistas por violação de descanso interjornada (mínimo 11h).
- Desperdício de tempo no RH/Administrativo com conferência manual.
- Presença de profissionais folguistas (sem setor/escala fixa) e variações na formatação dos PDFs.

## Arquitetura Básica
- python-extractor/: Extração de PDF/CSV, tratamento de dados (pandas, pdfplumber) e envio via JSON API.
- java-backend/: Motor de regras de negócio em Spring Boot, validação de horas, interjornada e sugestões de trocas.