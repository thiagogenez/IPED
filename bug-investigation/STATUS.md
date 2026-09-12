# Estado das contribuições

Os números de bugs abaixo são os do ranking da investigação; não são os números atribuídos pelo GitHub aos PRs.

| Bug | Estado | PR / branch |
| --- | --- | --- |
| 1 — estado residual no hashing | Corrigido e com testes; PR aberto no fork | [PR #2](https://github.com/thiagogenez/IPED/pull/2), `fix/hash-state-after-read-error` |
| 2 — downloads Firefox omitidos | Corrigido e com testes; PR aberto no fork | [PR #1](https://github.com/thiagogenez/IPED/pull/1), `fix/firefox-download-annotation-ids` |
| 3 — seek() em stream limitado | Adiado; reprodução e causa preservadas | [Prova](repros/SeekBoundaryRepro.java), [resultado](evidence/SeekBoundaryRepro.txt) |
| 4 — busca UTF-8 entre blocos | Adiado; reprodução e causa preservadas | [Prova](repros/HexUtf8Repro.java), [resultado](evidence/HexUtf8Repro.txt) |
| 5 — minutos do offset de datas | Adiado; reprodução e causa preservadas | [Prova](repros/DateTimezoneRepro.java), [resultado](evidence/DateTimezoneRepro.txt) |

Os PRs têm `thiagogenez/IPED:master` como destino. Ainda não foram enviados a `sepinf-inc/IPED`, portanto não representam aceite nem avaliação pela comunidade oficial.

## Validação dos PRs

- Firefox: quatro testes com parser completo. Antes da correção, duas falhas `expected:<3> but was:<0>`; depois, zero falhas. Executado em Java 24.0.1 e novamente em Liberica JDK 11.0.28 Full.
- Hash: seis testes com HashTask/Item reais e digests JDK/Bouncy Castle. Os seis falham na versão original e passam com a correção, em Liberica JDK 11.0.28 Full.
- Maven 3.9.9; build dos módulos necessários via `-am`; `git diff --check` sem problemas. A suíte completa da aplicação e a interface gráfica não foram executadas.
- O teste do hash usou configuração temporária de mirrors para consultar Maven Central no lugar dos repositórios de voz. Versões das dependências e POMs não foram alterados.
- Nenhuma execução do GitHub Actions foi listada para a branch do Firefox na verificação após o push. A validação relatada é local; não foi habilitado nem modificado CI.

Logs: [Firefox antes](evidence/firefox-before.log), [Firefox depois](evidence/firefox-after.log), [Firefox Java 11](evidence/firefox-java11.log), [hash antes](evidence/hash-before.log), [hash depois](evidence/hash-after.log).

Descrições publicadas: [Firefox](prs/firefox.md) e [hash](prs/hash.md).

## Preparação para upstream

A [wiki do IPED](https://github.com/sepinf-inc/IPED/wiki/Contributing) orienta checar issues/trabalho existente, registrar uma issue e usar quatro espaços de indentação. Não existe um template de PR no checkout inspecionado. As descrições adotam título por componente, problema, reprodução, esperado/atual, correção e validação, aproveitando a estrutura de uma contribuição pequena e revisável.

Antes de enviar ao repositório oficial, registrar as duas issues com essas reproduções e relacionar os PRs. Considerar a sobreposição da correção de HashTask com o [PR oficial #2918](https://github.com/sepinf-inc/IPED/pull/2918). Nenhuma issue ou comentário foi publicado no repositório oficial durante esta etapa.

Este registro e as provas dos bugs adiados estão preservados na branch `investigation/forensic-bug-backlog` do fork. Eles não fazem parte dos diffs dos dois PRs de correção.

## Melhorias após revisão crítica

- PR #1: adicionados dois controles negativos de anotações alheias nos IDs 3/4. Seis testes passam; os dois novos detectam uma consulta deliberadamente incorreta que aceita também os IDs numéricos. SQL de produção preservada.
- PR #2: corrigida espera infinita caso execute lance Error antes de iniciar um update. Contagens de submissões falhas e ainda não tentadas são liberadas; tarefas aceitas terminam antes da limpeza e da propagação do Error. Os dois novos cenários fatais deram timeout antes do ajuste; todos os nove testes passam depois, incluindo rejeição normal e três algoritmos simultâneos.
- Maven 3.9.9 / Liberica JDK 11.0.28 Full. Nenhum esgotamento real de recursos, alteração do pool global ou teste de GUI. Logs desta rodada estão em evidence/iped-*-revision-*.log.

## Issue existente #2940 — cache de regex

Correção preparada na branch `fix/regex-cache-failure`, commit `cea9fc1`, como [PR draft #3 no fork](https://github.com/thiagogenez/IPED/pull/3), relacionado à [issue upstream #2940](https://github.com/sepinf-inc/IPED/issues/2940). Publicação upstream segue pausada por orientação do usuário.

Antes: dez testes, seis erros. Depois: dez testes sem falhas ou erros; incluindo validadores próximos, 71 testes passaram em Maven/JDK 11. Cache corrompido é reconstruído; gravação temporária e substituição atômica preservam o cache anterior; falhas do cache não impedem matching. SOE controlado no limite de serialização; outros erros fatais continuam propagando.

Descrição: [regex-cache.md](prs/regex-cache.md). Logs: [antes](evidence/regex-cache-before.log), [depois](evidence/regex-cache-after.log), [validadores](evidence/regex-cache-validation.log). GUI, Windows e configuração grande do autor não executados.
