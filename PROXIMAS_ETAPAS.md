# Proximas etapas

## 1. Arquivos pelo WebRTC

- Selecionar pelo seletor nativo do Android, sem permissao geral de armazenamento.
- Criar uma mensagem de arquivo com ID, nome, MIME type, tamanho e SHA-256 assinados.
- Enviar em blocos binarios de 32 KiB pelo DataChannel, sem carregar o arquivo inteiro na memoria.
- Salvar progresso e confirmar blocos para continuar apos reconexao.
- Validar tamanho, hash e remetente antes de disponibilizar o arquivo.
- Limite inicial: 25 MB. Aumentar apenas apos medir memoria e estabilidade nos dois aparelhos.

## 2. Perfis e fotos

- Separar `publicName` de `localAlias`; renomear um contato nunca altera o nome assinado por ele.
- Perfil proprio: nome publico, foto escolhida pelo Photo Picker, controle de compartilhamento e codigo de seguranca.
- Normalizar a foto para WebP de no maximo 512 x 512 e calcular SHA-256 antes de compartilhar.
- A identidade publica passa a assinar apenas hash, MIME type e tamanho da foto, nunca os bytes.
- No handshake, cada aparelho anuncia o hash atual. O contato solicita a foto somente se o hash local for diferente.
- Transferir a foto pelo mesmo protocolo de blocos dos arquivos e salvar em cache pelo hash.
- Perfil do contato: foto recebida, nome publico, apelido local, Peer ID resumido e fingerprint.
- Tocar no avatar do header abre o perfil do contato; tocar no avatar proprio abre o perfil local.
- Migrar contatos atuais mantendo `displayName` como `localAlias` e preencher `publicName` no proximo pacote assinado recebido.

## 3. Backup no Google Drive

- Implementado pelo seletor de arquivos do Android, sem OAuth ou acesso geral ao Drive.
- O usuario escolhe um documento no Google Drive e o app mantem permissao para sobrescreve-lo.
- O conteudo usa o mesmo formato criptografado por senha do backup manual.
- O automatico roda apos alteracoes, respeitando intervalo minimo de 6 horas, e oferece "Fazer backup agora".
- A tela mostra destino, ultimo backup, proximo backup, falha e alteracao de destino.
- Depois de reinstalar, basta restaurar o arquivo e escolher novamente o destino automatico.

## 4. Atualizacoes pelo GitHub

- Repositorio: `https://github.com/Dojassola/projeto_mensageiro`.
- O app consulta a ultima GitHub Release uma vez por dia ou imediatamente pelo botao "Verificar agora", baixa pelo `DownloadManager` e valida o SHA-256 do asset.
- O Android valida a assinatura do APK e exige confirmacao da pessoa para instalar.
- `.github/workflows/release.yml` publica `Mensageiro.apk` ao receber uma tag igual a versao, por exemplo `v0.17.1`.
- Commits comuns nao publicam versoes. A publicacao acontece com `git tag v0.17.1` e `git push origin v0.17.1`.
- Para manter os dados instalados, o Secret `ANDROID_KEYSTORE_BASE64` deve conter `%USERPROFILE%\.android\debug.keystore` em Base64.
- A chave continua fora do repositorio. A 0.17.1 sera o primeiro teste de atualizacao nos dois aparelhos.

## Ordem

1. Validar o backup de 6 horas no Google Drive nos dois aparelhos.
2. Confirmar a atualizacao da 0.17.0 para a 0.17.1 pelo proprio aplicativo.
3. Planejar o proximo conjunto de recursos depois desses dois testes.
