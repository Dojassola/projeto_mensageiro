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

- Reutilizar exatamente o backup atual criptografado por senha.
- Salvar no `appDataFolder` do Drive, sem acesso aos demais arquivos do usuario.
- Executar com WorkManager no maximo uma vez por dia e oferecer "Fazer backup agora".
- Nunca iniciar upload por mensagem, confirmacao de leitura ou mudanca de tela.
- Manter backup local e Drive como destinos alternativos do mesmo formato.
- Requer configurar OAuth no Google Cloud com o package `com.mensageiro` e o certificado definitivo de release.

## 4. Atualizacoes pelo GitHub

- Repositorio: `https://github.com/Dojassola/projeto_mensageiro`.
- O app consulta a ultima GitHub Release uma vez por dia ou imediatamente pelo botao "Verificar agora", baixa pelo `DownloadManager` e valida o SHA-256 do asset.
- O Android valida a assinatura do APK e exige confirmacao da pessoa para instalar.
- `.github/workflows/release.yml` publica `Mensageiro.apk` ao receber uma tag igual a versao, por exemplo `v0.17.0`.
- Commits comuns nao publicam versoes. A publicacao acontece com `git tag v0.17.0` e `git push origin v0.17.0`.
- Para manter os dados instalados, o Secret `ANDROID_KEYSTORE_BASE64` deve conter `%USERPROFILE%\.android\debug.keystore` em Base64.
- A chave continua fora do repositorio. A 0.16 foi a ultima versao enviada manualmente aos dois aparelhos.

## Backup no Drive sem OAuth

- O seletor de arquivos do Android ja oferece Google Drive como destino quando o aplicativo Drive esta instalado.
- O automatico mantem permissao sobre o documento escolhido e sobrescreve esse mesmo backup criptografado.
- A tela mostra o provedor, ultimo backup, proximo backup, alteracao de destino e execucao imediata.
- Depois de reinstalar, basta restaurar o arquivo do Drive e escolher novamente o destino automatico.

## Ordem

1. Confirmar desempenho do cache, sync incremental e backup de 6 horas.
2. Implementar mensagens de arquivo e retomada.
3. Implementar perfis/fotos sobre o protocolo de arquivos.
4. Adicionar o Secret da assinatura e publicar a primeira release.
5. Configurar OAuth e integrar backup diario no Google Drive.
