package org.example;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.example.GumTreeExtractor.saveProjectDiffToJson;
import static org.example.Helpers.extractJdkSqlSources;
import static org.example.JarComparator.compareJarfiles;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {




/*
        String rootArchiveFolder = "src/data/JavaLibraries/logging-log4j2_releases";
        String extractBaseDir = "build/extracted/logging-log4j2_releases"; // where each zip will be unpacked
        String language = "java"; //

        try {
            compareArchivesInRoot(rootArchiveFolder, extractBaseDir, language);
            System.out.println("Pairwise comparisons completed.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }*/


      //diffProjects();

        File before = new File("src/data/jars/log4j-1.2.16.jar");
        File after = new File("src/data/jars/log4j-1.2.17.jar");
        String beforeV = "1.2.16";
        String afterV = "1.2.17";
        compareJarfiles(before,beforeV,after,afterV);




    }


    public static void diffProjects(){
        String srcPath = "src/data/toys/toy1";
        String dstPath = "src/data/toys/toy2";


        try {
            saveProjectDiffToJson(srcPath, dstPath, "results/tutorial/diff_example.json", "java");
            System.out.println("Project diff JSON written to results/diff.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }
}
