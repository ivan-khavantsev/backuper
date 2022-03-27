package com.backuper;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class Main2 {

    /**
     * TODO:
     * Разобраться. Если есть дубли файлов, то не добавлять их для перемещения.
     * Автоматически создавать следующий архив по достижению предела размера
     *
     */

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        System.out.println("Start backuper");

        String dataFolderPath = "D:/backup-test/data";
        String backupFolderPath = "D:/backup-test/backup";
        Long maxBackupFileSize = 1073741824L; // 1GB

        // Читаем все файлы .backup.7z из каталога бэкапа и составляем map <Hash, Item>
        File backupDir = new File(backupFolderPath);
        File[] backupFiles = backupDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String filename) {
                return (filename.endsWith(".backup.7z"));
            }
        });

        Map<String, BackupItem> itemsInBackup = new HashMap<>();

        // Создаём новый map <Hash, Item> файлов которые не содержатся в предыдущем.
        Map<String, BackupItem> itemsToBackup = new HashMap<>();
        // Создаём новый map <Hash, Item> файлов которые СОДЕРЖАТСЯ в предыдущем, но имеют новый путь. Их мы архивировать не будем, но запишем новый путь.
        Map<String, BackupItem> itemsToMove = new HashMap<>();

        for (File archive : backupFiles) {
            System.out.println("Exist backup file " + archive.getName());
            // Читаем фалы 7z. Достаём rename.txt, составляем список

            try (SevenZFile sevenZFile = new SevenZFile(archive)) {
                SevenZArchiveEntry entry;
                while ((entry = sevenZFile.getNextEntry()) != null) {
                    if(entry.getName().endsWith(".rename.txt")){
                        byte[] content = new byte[(int) entry.getSize()];
                        sevenZFile.read(content);
                        String renameData = new String(content);

                        Scanner scanner = new Scanner(renameData);
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine();
                            if(line.length() > 0){
                                String[] lineItems = line.split(":");
                                BackupItem existsBackupItem = new BackupItem();
                                String hash = lineItems[0].intern();
                                existsBackupItem.setHash(hash);
                                String path = lineItems[1].intern();
                                existsBackupItem.setFilePath(path);
                                itemsInBackup.put(hash, existsBackupItem);
                            }
                        }
                        scanner.close();


                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // При добавлении хеша обязательно .intern();
        }


        // Берём все файлы из каталога с данными
        File dataDir = new File(dataFolderPath);
        Collection<File> files = FileUtils.listFiles(
                dataDir,
                FileFileFilter.FILE,
                DirectoryFileFilter.DIRECTORY
        );

        Long totalBackupFileSize = 0L;

        for (File file : files) {
            BackupItem backupItem = new BackupItem();
            String relativePath = Paths.get(dataDir.getAbsolutePath()).relativize(Paths.get(file.getAbsolutePath())).toString();
            relativePath = relativePath.replace("\\", "/").intern();
            backupItem.setFilePath(relativePath);
            MessageDigest shaDigest = MessageDigest.getInstance("SHA-256");
            String shaChecksum = getFileChecksum(shaDigest, file).intern();
            backupItem.setHash(shaChecksum);

            if (itemsInBackup.containsKey(shaChecksum)) {
                // Если такой файл уже есть в бэкапе, но путь изменился, добавляем в список для перемещения
                if (!itemsInBackup.get(shaChecksum).getFilePath().equals(relativePath)) {

                    System.out.println(itemsInBackup.get(shaChecksum).getFilePath());
                    System.out.println(relativePath);

                    System.out.println("File " + shaChecksum + " adding for move");
                    itemsToMove.put(shaChecksum, backupItem);
                }
            } else {

                totalBackupFileSize += file.length();
                if(totalBackupFileSize > maxBackupFileSize){
                    System.out.println("Backup size exceed");
                    break;
                }

                backupItem.setFile(file);
                System.out.println("File " + shaChecksum + " adding for backup");
                itemsToBackup.put(shaChecksum, backupItem);
            }
        }

        StringBuilder renameStringBuilder = new StringBuilder();
        for (BackupItem backupItem : itemsToBackup.values()){
            renameStringBuilder.append(backupItem.getHash());
            renameStringBuilder.append(":");
            renameStringBuilder.append(backupItem.getFilePath());
            renameStringBuilder.append("\n");
        }

        for (BackupItem backupItem : itemsToMove.values()){
            renameStringBuilder.append(backupItem.getHash());
            renameStringBuilder.append(":");
            renameStringBuilder.append(backupItem.getFilePath());
            renameStringBuilder.append("\n");
        }

        //System.out.println(renameStringBuilder.toString());

        Long currentTime = System.currentTimeMillis();

        SevenZOutputFile sevenZOutput = new SevenZOutputFile(new File(backupFolderPath + "/backup" + currentTime + ".backup.7z"));
        sevenZOutput.setContentCompression(SevenZMethod.COPY);


        for (BackupItem backupItem: itemsToBackup.values()){
            SevenZArchiveEntry fileEntry = sevenZOutput.createArchiveEntry(backupItem.getFile(), backupItem.getHash());
            sevenZOutput.putArchiveEntry(fileEntry);
            sevenZOutput.write(Files.readAllBytes(backupItem.getFile().toPath()));
            sevenZOutput.closeArchiveEntry();
        }

        File renameFile = new File("backup"+currentTime+".rename.txt");
        SevenZArchiveEntry renameEntry = sevenZOutput.createArchiveEntry(renameFile, renameFile.getName());
        renameEntry.setCreationDate(currentTime);

        sevenZOutput.putArchiveEntry(renameEntry);
        sevenZOutput.write(renameStringBuilder.toString().getBytes(StandardCharsets.UTF_8));
        sevenZOutput.closeArchiveEntry();

        sevenZOutput.finish();



        // Добавить все файлы с именем <хеш>(.<расширение> надо ли?)
        // Добавить файл rename-<timestamp>.brt в котором hash:путь к файлу

        System.out.println("Finish backuper");
    }


    private static String getFileChecksum(MessageDigest digest, File file) throws IOException {
        //Get file input stream for reading the file content
        FileInputStream fis = new FileInputStream(file);

        //Create byte array to read data in chunks
        byte[] byteArray = new byte[1024];
        int bytesCount = 0;

        //Read file data and update in message digest
        while ((bytesCount = fis.read(byteArray)) != -1) {
            digest.update(byteArray, 0, bytesCount);
        }
        ;

        //close the stream; We don't need it now.
        fis.close();

        //Get the hash's bytes
        byte[] bytes = digest.digest();

        //This bytes[] has bytes in decimal format;
        //Convert it to hexadecimal format
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
        }

        //return complete hash
        return sb.toString();
    }
}
