# Validação ampliada — issue #2940 / PR fork #3

Código: cea9fc1a36a935fbe908837034a6ed3c5422b40a. Base: ceb8ed90bc482b1bf52af674cc581b8a8e2ba7eb.

## Resultado

Sem regressão identificada nesta validação. A suíte sem exclusões não fica verde no macOS ARM64 por dois testes SevenZip, com falhas idênticas no master. Com somente essa classe excluída, `verify` terminou com BUILD SUCCESS em todos os módulos: 194 testes de parsers (15 ignorados) e 100 testes do engine. Isso corresponde a 279 testes aprovados e 15 ignorados; os dois SevenZip ficaram explicitamente excluídos.

Windows Server 2022 / Liberica JDK 11: dez testes do cache aprovados. Distribuição portátil compilada e quatro casos sintéticos processados via CLI, com cache ausente, válido, vazio e bloqueado para substituição. Todos saíram com código zero e exatamente uma ocorrência correta no índice Lucene. Os logs confirmam os quatro caminhos esperados de cache.

Execução: https://github.com/thiagogenez/IPED/actions/runs/34719146467
Primeira validação Windows: https://github.com/thiagogenez/IPED/actions/runs/34718223985
Scripts: https://github.com/thiagogenez/IPED/tree/validation/regex-cache-windows/.github/scripts

## Evidências

- iped-regex-full-verify.log: execução integral inicial no PR.
- iped-regex-master-parsers.log: mesmos testes de parsers no master.
- iped-regex-pr-failures.json e iped-regex-master-failures.json: nomes, tipos e mensagens são idênticos.
- iped-regex-verify-excluding-sevenzip.log: verify com apenas SevenZip excluído, BUILD SUCCESS.
- iped-regex-windows.log: primeira execução dos dez testes Windows.
- windows-smoke/summary.json, *-index.txt, *-processing.log e *-console.log: quatro processamentos e leitura dos índices.

## Limites

GUI, leitura de imagens de disco e configuração grande do autor não testadas. Os 15 testes ignorados pela suíte/ambiente não contam como aprovados. O processamento macOS não concluiu por bibliotecas nativas FFmpeg/Zstandard; por isso a prova ponta a ponta foi feita no Windows. Configurações reduzidas e evidências são sintéticas, isoladas das pastas do usuário.

O diff da correção não foi alterado nesta etapa. A branch de CI é separada. Publicação upstream continua pausada; PR #3 permanece draft no fork.
