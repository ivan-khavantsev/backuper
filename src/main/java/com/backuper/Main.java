package com.backuper;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;

import java.io.*;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

public class Main {

    private String dataFolder;
    private String backupFolder;
    private String baseBackupName;
    private String compressLevel = "0";

    private String backupFilename;

    private Boolean fullBackup = false;


    public static String md5File(String filename) throws IOException {
        FileInputStream fis = new FileInputStream(new File(filename));
        String md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis).toLowerCase();
        fis.close();
        return md5;
    }

    private static long doChecksum(String fileName) throws Exception {
        CheckedInputStream cis = new CheckedInputStream(new FileInputStream(fileName), new CRC32());
        byte[] buf = new byte[128];
        while (cis.read(buf) >= 0) {
        }
        return cis.getChecksum().getValue();
    }


    private void archiveFiles(File archive, Map<String, Long> map) throws Exception {
        SevenZFile archiveFile = new SevenZFile(archive);
        SevenZArchiveEntry entry;
        try {
            while ((entry = archiveFile.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()) {
                    map.put(name.intern(), entry.getCrcValue());
                }
            }
        } finally {
            archiveFile.close();
        }
    }

    private Set<String> getDifferenceEntries(File dir, Map<String, Long> map) throws Exception {

        Collection<File> files = FileUtils.listFiles(
                dir,
                FileFileFilter.FILE,
                DirectoryFileFilter.DIRECTORY
        );

        Set<String> diffSet = new HashSet<>();
        for (File file : files) {
            String relativePath = Paths.get(dir.getAbsolutePath()).relativize(Paths.get(file.getAbsolutePath())).toString();
            relativePath = relativePath.replace("\\", "/").intern();

            if (map.containsKey(relativePath)) {
                Long crc32File = doChecksum(file.getAbsolutePath());
                if (!crc32File.equals(map.get(relativePath))) {
                    diffSet.add(relativePath);
                }
            } else {
                diffSet.add(relativePath);
            }
        }
        return diffSet;
    }


    private File[] finder(String dirName) {
        File dir = new File(dirName);

        return dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String filename) {
                return (filename.startsWith(baseBackupName) && filename.endsWith(".7z"));
            }
        });

    }

    private void start(String[] args) throws Exception {

        if (args.length < 3) {
            System.out.println("Usage: java -jar backuper.jar \"DataFolder\" \"BackupFolder\" \"BaseBackupName\" [CompressLevel 0-9]");
            System.exit(1);
        }

        this.dataFolder = args[0] + "/";
        this.backupFolder = args[1] + "/";
        this.baseBackupName = args[2];

        if (args.length > 3) {
            compressLevel = args[3];
        }

        System.out.println();
        System.out.println("Data folder:    " + dataFolder);
        System.out.println("Backup folder:  " + backupFolder);
        System.out.println("Base name:      " + baseBackupName);

        System.out.println("Check files in backup");
        Map<String, Long> filesInArchives = new HashMap<>();

        File[] archives = finder(this.backupFolder);
        if (archives.length != 0) {
            for (File archive : archives) {
                archiveFiles(archive, filesInArchives);
            }
        } else {
            this.fullBackup = true;
        }


        System.out.println("Check differences");
        File dataFolder = new File(this.dataFolder);
        Set<String> diffFiles = getDifferenceEntries(dataFolder, filesInArchives);


        System.out.println("Archiving...");
        this.archive(diffFiles);

    }


    private void archive(Set<String> files) throws Exception {

        if (!files.isEmpty()) {

            DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
            Date date = new Date();
            String formattedDate = dateFormat.format(date);

            String listFileName = this.backupFolder + "filelist." + formattedDate + ".txt";
            try {
                String appendix = (this.fullBackup) ? "full" : "inc";
                this.backupFilename = this.backupFolder + this.baseBackupName + "." + formattedDate + "." + appendix + ".7z";


                PrintWriter writer = new PrintWriter(listFileName, "UTF-8");
                for (String file : files) {
                    writer.println(file);
                }
                writer.close();

                String[] commands = {"7z", "a", "-mx" + compressLevel, this.backupFilename, "-i@" + listFileName};

                Runtime rt = Runtime.getRuntime();
                Process proc = rt.exec(commands, null, new File(this.dataFolder));
                BufferedReader stdInput = new BufferedReader(new
                        InputStreamReader(proc.getInputStream()));

                String s = null;
                while ((s = stdInput.readLine()) != null) {
                    System.out.println(s);
                }

                par2Backup();

            } finally {
                new File(listFileName).delete();
            }
        } else {
            System.out.println("Nothing to backup");
        }


    }

    private void par2Backup() throws Exception {
        Runtime rt = Runtime.getRuntime();
        String[] commands = {"par2j", "create", "/rr10", "/in", this.backupFilename + ".par2", this.backupFilename};
        Process proc = rt.exec(commands, null, new File(this.backupFolder));

        BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(proc.getInputStream()));

        String s = null;
        while ((s = stdInput.readLine()) != null) {
            System.out.println(s);
        }
    }

    public static void main(String[] args) throws Exception {
        new Main().start(args);
    }
}
