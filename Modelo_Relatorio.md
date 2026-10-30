# Análise comparativa de algoritmos de ordenação em ambientes seriais e paralelos

**Disciplina:** [preencher]  
**Integrantes:** [preencher]  
**Instituição:** [preencher]  
**Data:** [preencher]

## Resumo

Este trabalho implementa e compara Bubble Sort, Merge Sort, Selection Sort e Insertion Sort em versões seriais e paralelas, utilizando Java e recursos nativos da plataforma. Os testes variam tamanho e distribuição dos dados de entrada, quantidade de threads e número de amostras. Os tempos são registrados em CSV e apresentados em tabelas e gráficos. **Preencha este resumo após executar os experimentos**, informando a configuração da máquina, os principais resultados numéricos e as conclusões observadas, sem antecipar resultados que ainda não foram medidos.

## 1. Introdução

Explique a motivação para comparar algoritmos de ordenação serial e paralela e a relevância do paralelismo em processadores multicore.

Apresente brevemente os algoritmos:

- **Bubble Sort:** compara elementos adjacentes e troca os que estão fora de ordem; sua versão serial clássica tem complexidade quadrática no pior caso.
- **Merge Sort:** divide o vetor, ordena as partes e intercala os resultados; a versão clássica tem complexidade temporal de ordem \(O(n\log n)\).
- **Selection Sort:** seleciona repetidamente o menor elemento da parte ainda não ordenada; a versão serial clássica tem complexidade quadrática.
- **Insertion Sort:** insere cada elemento na posição apropriada do prefixo ordenado; a versão serial clássica tem complexidade quadrática no pior caso e pode se beneficiar de entradas quase ordenadas.

Descreva as variantes paralelas utilizadas no programa. O Bubble Sort paralelo usa fases odd-even transposition; o Merge Sort utiliza tarefas recursivas com `ForkJoinPool`; o Selection Sort paraleliza a busca pelo menor elemento da faixa restante; e o Insertion Sort paralelo ordena blocos com insertion sort e combina os blocos por intercalação paralela. Essa distinção é importante para interpretar os resultados, pois nem todas as variantes paralelas mantêm exatamente a mesma estrutura interna do algoritmo serial.

## 2. Metodologia

### 2.1 Ambiente experimental

Preencha a tabela com os dados reais do computador e do ambiente em que os testes foram realizados.

| Parâmetro | Valor observado |
|---|---|
| Processador | [modelo] |
| Núcleos / threads de hardware | [informar, se conhecido] |
| Memória RAM | [informar] |
| Sistema operacional | [versão] |
| Versão do Java | [saída de `java -version`] |
| Data e horário dos testes | [informar] |

### 2.2 Configuração dos experimentos

Informe todos os tamanhos de vetor, tipos de entrada, quantidades de threads e número de amostras utilizados. O programa exige no mínimo cinco amostras por configuração. Tipos de entrada disponíveis: aleatório, ordenado, inverso, quase ordenado e muitos valores repetidos.

| Fator | Valores utilizados |
|---|---|
| Tamanhos dos vetores | [preencher] |
| Distribuições de entrada | [preencher] |
| Threads nas execuções paralelas | [preencher] |
| Amostras por configuração | [preencher; mínimo 5] |
| Algoritmos selecionados | [preencher] |

A entrada utilizada para cada algoritmo e modo corresponde à mesma combinação de tamanho, distribuição e amostra. O programa cria a cópia de trabalho antes de iniciar a medição, mede o tempo com `System.nanoTime()` e verifica se a saída está ordenada. A criação do pool de threads é feita fora da região cronometrada, enquanto o custo de escalonamento das tarefas de cada algoritmo paralelo permanece incluído.

### 2.3 Métricas e análise estatística

Foram coletados tempo de execução em nanossegundos e milissegundos. Para cada configuração, o programa calcula média, mediana, desvio-padrão populacional, mínimo e máximo.

O speedup é calculado por:

\[
S = \frac{T_{serial}}{T_{paralelo}}
\]

Quando \(S > 1\), a média da versão paralela é menor; quando \(S < 1\), a versão paralela foi mais lenta. O speedup deve ser comparado para o mesmo algoritmo, tamanho de vetor e distribuição.

### 2.4 Reprodutibilidade e limitações

Descreva a carga do computador durante os testes, eventuais execuções repetidas e fatores que podem influenciar os resultados: compilação JIT, coleta de lixo, frequência dinâmica do processador, tarefas de fundo e custo de sincronização. Não misture resultados de máquinas ou configurações diferentes sem identificá-los.

## 3. Resultados e discussão

Insira os gráficos exportados ou produzidos a partir dos CSVs e discuta os valores medidos. Evite afirmar que um algoritmo é sempre mais rápido; relacione os resultados ao tamanho e à distribuição dos dados.

### 3.1 Comparação serial entre algoritmos

[Inserir gráfico de tempo médio por tamanho para as quatro versões seriais.]

**Discussão:** [Identifique os algoritmos mais rápidos e mais lentos para cada faixa de tamanho, usando valores medidos. Relacione o observado às complexidades teóricas.]

### 3.2 Versões seriais versus paralelas

[Inserir gráficos por algoritmo, distinguindo versão serial e quantidades de threads.]

**Discussão:** [Informe em quais combinações houve ganho ou perda, incluindo valores de média e speedup.]

### 3.3 Efeito da quantidade de threads

[Inserir gráfico de tempo médio ou speedup em função do número de threads.]

**Discussão:** [Analise se o aumento de threads reduziu o tempo. Considere granularidade das tarefas, sincronização, trabalho sequencial restante e limites do hardware.]

### 3.4 Efeito da distribuição de entrada

[Compare dados aleatórios, ordenados, inversos, quase ordenados e com valores repetidos, conforme os cenários executados.]

**Discussão:** [Explique os efeitos observados e ressalte que Bubble Sort serial pode encerrar cedo em dados ordenados, ao passo que outras distribuições podem exigir muito mais operações.]

### 3.5 Síntese quantitativa

| Algoritmo | Tamanho / entrada | Melhor configuração observada | Tempo médio serial (ms) | Tempo médio paralelo (ms) | Speedup |
|---|---|---|---:|---:|---:|
| [preencher] | [preencher] | [preencher] | [valor medido] | [valor medido] | [valor calculado] |

Use os CSVs detalhado e resumido gerados pela aplicação como fonte dos valores. Informe unidades, número de amostras e condições de cada gráfico.

## 4. Conclusão

Responda aos objetivos do trabalho com base nos resultados realmente obtidos. Indique quais algoritmos e configurações foram mais adequados para os cenários medidos, em que casos o paralelismo ajudou ou não ajudou e quais limitações permaneceram. Não generalize resultados específicos para todos os processadores ou tamanhos de entrada. Sugira como trabalho futuro testar outros tamanhos, mais distribuições, mais máquinas ou técnicas de medição com maior controle experimental.

## Referências

CORMEN, Thomas H.; LEISERSON, Charles E.; RIVEST, Ronald L.; STEIN, Clifford. *Introduction to Algorithms*. 4. ed. Cambridge, MA: MIT Press, 2022.

ORACLE. *Java Platform, Standard Edition 17 API Specification: ForkJoinPool*. Disponível em: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ForkJoinPool.html. Acesso em: [preencher].

ORACLE. *Java Platform, Standard Edition 17 API Specification: Swing package*. Disponível em: https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/swing/package-summary.html. Acesso em: [preencher].
