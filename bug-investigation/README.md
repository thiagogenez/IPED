# Inspeção de bugs: evidências e ordem de contribuição

**Recomendação: abrir primeiro um PR para os downloads omitidos do Firefox. Reportar com maior urgência a contaminação de hashes.**

O primeiro é uma contribuição pequena, com perda de informação demonstrável no próprio fixture do projeto. O segundo tem maior gravidade, mas exige cuidado com concorrência e há um PR aberto alterando a mesma classe.

Foram confirmados cinco defeitos independentes. A inspeção se concentrou em leitura de evidências, ciclo de vida de hashing, consultas de histórico/downloads de navegadores, normalização de datas e busca hexadecimal. Também foram lidos caminhos de deduplicação, exportação CSV e MBOX. Não foi uma auditoria exaustiva de todos os módulos; suspeitas sem reprodução não entram neste ranking.

| Prioridade para reportar | Ordem sugerida de PR | Bug | Impacto | Esforço/risco estimado da correção |
| --- | --- | --- | --- | --- |
| 1 | 2 | HashTask reutiliza estado após erro de leitura | Um arquivo íntegro recebe hash de conteúdo diferente | Médio: esperar tarefas pendentes, limpar digests e estado ED2K; considerar PR #2918 |
| 2 | 1 | Firefox exige IDs fixos para anotações de downloads | Downloads existentes desaparecem da extração especializada | Baixo: resolver IDs pelo nome; fixture existente já demonstra a falha |
| 3 | 3 | seek() não atualiza posição lógica no stream limitado | Leitura fora dos limites e EOF prematuro em itens recuperados | Baixo, mas a classe é compartilhada por vários consumidores |
| 4 | 4 | Busca hexadecimal mistura caracteres e bytes | Ocorrência UTF-8 existente não é encontrada; destaque fica curto | Baixo/médio: manter sobreposição e offsets em bytes |
| 5 | 5 | DateUtil ignora minutos do offset com milissegundos | Eventos recebem horário UTC incorreto | Baixo/médio: preservar formatos aceitos e testar offsets/fracionários |

As classificações de impacto e esforço são avaliações técnicas, não estatísticas de frequência de uso.

## 1. Hash incorreto no arquivo seguinte a uma falha de leitura

**Local:** [HashTask.java](../iped-engine/src/main/java/iped/engine/task/HashTask.java), especialmente o `catch` da linha 199. A instalação por worker aparece em [TaskInstaller.java](../iped-engine/src/main/java/iped/engine/task/TaskInstaller.java); a instância da tarefa é reutilizada.

**Gatilho:** um arquivo A entrega um prefixo legível e depois causa IOException. O mesmo HashTask processa um arquivo B íntegro.

**Reprodução:** o teste usa o HashTask original. Uma implementação mínima de IItem entrega os streams; a implementação MD5 é a do JDK. Um latch garante que o prefixo foi incorporado ao digest antes de lançar a falha de leitura. Isso torna determinística uma ordem de execução possível no processamento normal.

- Prefixo legível de A: `readable prefix of damaged evidence|`.
- Conteúdo integral de B: `intact evidence B`.
- Esperado para B: `C08E81E668BC621EF8882D8297C8D226`.
- Atual para B: `B1D1DE0300A2F6FC2CDA6E6AB3439EA7`.
- O resultado atual é exatamente o MD5 de `prefixo(A) || B`.
- B não recebe o atributo `ioError`. Uma execução posterior bem-sucedida volta a produzir o hash correto, demonstrando o alcance do estado residual.

**Efeito observado:** hash publicado incorreto no segundo item. **Consequências inferidas dos consumidores:** consultas a bases de hashes, correlações e deduplicação podem tomar decisões usando esse valor incorreto. A deduplicação completa não foi executada neste teste.

**Causa:** o caminho de erro não chama reset dos digests nem limpa o estado ED2K; o reset implícito de `digest()` só acontece no caminho bem-sucedido. Uma correção precisa também aguardar operações de hash já submetidas antes de limpar/reutilizar o estado.

**Prova:** [teste executável](repros/HashFailureRepro.java) e [saída completa](evidence/HashFailureRepro.txt). Controle com B numa tarefa nova passa; B após A falha; B na execução seguinte passa.

**Título sugerido:** `HashTask retains digest state after an I/O error and computes an incorrect hash for the next item`.

Há [PR #2918 aberto](https://github.com/sepinf-inc/IPED/pull/2918) otimizando HashTask. O diff consultado não resolve este erro, mas existe sobreposição de código. Vale reportar a reprodução antes de preparar o PR de correção.

## 2. Downloads do Firefox omitidos por IDs fixos

**Local:** [FirefoxSqliteParser.java](../iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/browsers/firefox/FirefoxSqliteParser.java), `getDownloads()`, linhas 581–596.

**Gatilho:** as anotações `downloads/destinationFileURI` e `downloads/metaData` têm IDs diferentes de 3 e 4. Isso ocorre no próprio [test_places.sqlite](../iped-parsers/iped-parsers-impl/src/test/resources/test-files/test_places.sqlite) do projeto: os IDs são 1 e 2.

**Reprodução:** o script extrai a consulta diretamente do fonte, sem reimplementá-la, e a executa com SQLite no fixture original aberto somente para leitura. Compara com uma consulta que resolve as anotações pelo nome. Também testa cópias em memória com IDs renumerados de forma consistente.

| Banco | Esperado | Atual |
| --- | --- | --- |
| Fixture original, IDs 1/2 | 3 downloads | 0 downloads |
| Mesmo conteúdo, IDs 3/4 | 3 downloads | 3 downloads |
| Mesmo conteúdo, IDs 103/104 | 3 downloads | 0 downloads |

Os downloads omitidos são `processo-pf-master.zip` (53.274 bytes), `streeg-master.zip` (858 bytes) e `PTFRONTEND-master.zip` (4.675.214 bytes). Os três têm URL, destino e JSON contendo `endTime` e `fileSize`.

**Efeito observado:** a consulta que alimenta a lista de downloads retorna vazia sem erro SQL. Pelo fluxo do parser, esses registros não geram os itens especializados de download. O teste executa a consulta, não o parser Tika completo; não afirma que o conteúdo do banco ficou inacessível a todas as outras formas de análise.

**Causa:** IDs de anotações são chaves do banco, não constantes semânticas. O [código do Firefox](https://searchfox.org/firefox-main/source/toolkit/components/places/History.sys.mjs) insere nomes sem fixar IDs e resolve a chave pelo nome. A correção deve fazer o mesmo.

**Prova:** [reprodução SQL](repros/firefox_downloads.py) e [saída completa](evidence/FirefoxDownloadsRepro.txt). O teste atual `FirefoxSqliteParserTest` verifica favoritos; não contém asserções para downloads.

**Título sugerido:** `Firefox download extraction silently misses entries when annotation IDs are not 3 and 4`.

**Por que este PR primeiro:** reprodução com fixture já aceito no repositório, efeito direto na recuperação de informação, causa isolada e pequena superfície de alteração. O teste de regressão do PR deve verificar os três downloads extraídos pelo parser completo, além de uma variante de IDs; isso ainda não foi implementado aqui.

## 3. Limites de leitura incorretos após seek()

**Local:** [LimitedSeekableInputStream.java](../iped-utils/src/main/java/iped/utils/LimitedSeekableInputStream.java), linha 115. [Item.java](../iped-engine/src/main/java/iped/engine/data/Item.java), linhas 637 e 653, usa essa classe para representar trechos de arquivos pais/fontes de dados.

**Gatilho:** reposicionar a leitura de um item representado como trecho de um arquivo maior.

**Reprodução:** arquivo pai `HEADABCDESECRET`, início 4 e tamanho 5: o item é apenas `ABCDE`.

| Operação | Esperado | Atual |
| --- | --- | --- |
| seek(4), ler restante | E | ESECR |
| seek(5), ler um byte | EOF (-1) | S (83) |
| Ler tudo, seek(0), reler | ABCDE | vazio |
| available() após seek(4) | 1 | 5 |

**Efeito observado:** conteúdo externo ao item pode ser lido e conteúdo válido pode parecer ausente. A sequência de páginas usada pelo visualizador hexadecimal também falha: ler 2.001 páginas de 4.096 bytes e voltar à primeira, já expulsa do cache de 2.000 páginas, retorna zero bytes em vez de 4.096. A consequência visual foi inferida do código; a interface gráfica não foi aberta.

**Causa:** `seek()` move o stream interno, mas não atualiza `total`, usado por read(), available() e skip(). Atribuir `total = pos` depois de um seek bem-sucedido resolveu os seis cenários em uma cópia temporária na investigação anterior.

**Prova:** [teste executável](repros/SeekBoundaryRepro.java) e [saída completa](evidence/SeekBoundaryRepro.txt). Leitura sequencial sem reposicionamento passa. Itens servidos de um arquivo independente ou de memória não usam necessariamente esse wrapper.

**Título sugerido:** `LimitedSeekableInputStream.seek() causes premature EOF and reads outside carved item boundaries`.

## 4. Busca UTF-8 perde ocorrências entre blocos

**Local:** [HexSearcherImpl.java](../iped-app/src/main/java/iped/app/ui/viewers/HexSearcherImpl.java), linhas 118, 155, 163 e 227.

**Gatilho:** buscar texto com caracteres multibyte atravessando uma fronteira de 4.096 bytes. Este teste usa SeekableFileInputStream diretamente; é independente do bug do stream limitado.

**Reprodução:** arquivo de 8.192 bytes preenchido com pontos e `éé`, codificado em UTF-8 como `C3 A9 C3 A9`, inserido no offset 4.094. O worker original é chamado diretamente, com objetos de interface mínimos para capturar o resultado.

| Busca | Esperado | Atual |
| --- | --- | --- |
| Texto UTF-8 éé no offset 4094 | 1 ocorrência, 4 bytes | 0 ocorrências |
| Texto UTF-8 éé no offset 100 | 1 ocorrência, destaque de 4 bytes | 1 ocorrência, destaque de 2 bytes |
| Texto ASCII ABCD no offset 4094 | 1 ocorrência, 4 bytes | correto |
| Busca hexadecimal C3A9C3A9 no offset 4094 | 1 ocorrência, 4 bytes | correto |

**Efeito observado:** o mesmo conteúdo existe e é encontrado em modo hexadecimal, mas some na busca textual UTF-8. O tamanho de destaque também é incorreto fora das fronteiras.

**Causa:** o código dimensiona a sobreposição e o destaque usando `String.length()`, enquanto a busca percorre bytes produzidos por `getBytes(charset)`. Para éé, são dois caracteres e quatro bytes.

**Prova:** [teste executável](repros/HexUtf8Repro.java) e [saída completa](evidence/HexUtf8Repro.txt). Foi executado o algoritmo e o worker real com colaboradores visuais substituídos; não um teste da janela Swing completa.

**Título sugerido:** `Hex viewer text search misses UTF-8 matches spanning buffer boundaries`.

## 5. Datas deslocadas quando o fuso contém minutos

**Local:** [DateUtil.java](../iped-utils/src/main/java/iped/utils/DateUtil.java), linha 46. Consumidores incluem [IndexItem.java](../iped-engine/src/main/java/iped/engine/task/index/IndexItem.java), linhas 733 e 751, e a conversão de campos TimeStamp em UfedModelHandler.

**Gatilho:** timestamp ISO com fração de segundos e offset cujos minutos não são zero.

| Entrada | UTC esperado | UTC atual |
| --- | --- | --- |
| 2024-01-15T12:00:00.123+05:30 | 06:30:00.123Z | 07:00:00.123Z |
| 2024-01-15T12:00:00.123+05:45 | 06:15:00.123Z | 07:00:00.123Z |
| 2024-01-15T12:00:00.123-03:30 | 15:30:00.123Z | 15:00:00.123Z |

**Efeito observado:** o Date retornado está deslocado em 30 ou 45 minutos. O armazenamento/uso desse resultado em campos temporais pode alterar a correlação cronológica; a indexação Lucene completa não foi executada.

**Causa:** o padrão termina em um único `X`, que consome a parte de horas do offset; os minutos restantes não impedem o parse parcial de retornar um resultado. A [documentação Java 11](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/text/SimpleDateFormat.html) distingue os formatos X, XX e XXX.

**Prova:** [teste executável](repros/DateTimezoneRepro.java) e [saída completa](evidence/DateTimezoneRepro.txt), com `OffsetDateTime` como referência independente. Controles UTC, offset de hora inteira e timestamp sem milissegundos passam.

**Título sugerido:** `DateUtil drops offset minutes when parsing fractional-second timestamps`.

## Execução e limites da validação

Executar na raiz do projeto, com Python 3, RTK e JDK disponíveis:

```sh
rtk proxy python3 bug-investigation/repros/run_all.py
```

O comando executa todos os casos e grava os resultados em `bug-investigation/evidence/`. No código atual, o retorno 1 é esperado: os testes afirmam o comportamento correto e demonstram as divergências. O processo continua para coletar todos os casos. Erros de preparação/compilação devem ser distinguidos das asserções descritas nos logs.

Ambiente executado: JDK 24.0.1 e SQLite do Python. As classes de produção Java são compiladas sem alterações; configurações, logging, IItem e colaboradores de interface têm implementações mínimas explicitadas em [run_java.py](repros/run_java.py). Nenhuma implementação de MD5, datas, busca ou leitura é substituída. Firefox é validado em nível de SQL real. Não foi feito build Maven completo, processamento integral de um caso ou teste visual da aplicação.

Os cinco fontes locais coincidem com os blobs do master oficial consultado. A [checagem upstream](evidence/upstream-check.md) registra hashes, buscas de duplicatas e referências. Não encontrar uma issue nessas buscas não garante ineditismo.

Esta pasta preserva a investigação inicial, com os fontes originais. As correções dos bugs 1 e 2 foram preparadas em branches separadas e publicadas em PRs no fork. Consulte [STATUS.md](STATUS.md) para links, validação e os bugs 3–5 adiados.
