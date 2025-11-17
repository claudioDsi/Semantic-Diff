package org.example;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.example.GumTreeExtractor.saveProjectDiffToJson;


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

        String srcPath = "src/data/toys/toy1";
        String dstPath = "src/data/toys/toy2";


        /*
        String srcPath = "src/data/toys/toy1";
        String dstPath = "src/data/toys/toy2";

        try {
            exportProjectSourceTrees(srcPath,dstPath, "build/exportedTree_example.json", "java");
        } catch (IOException e){
            e.printStackTrace();
        }*/



        try {
            saveProjectDiffToJson(srcPath, dstPath, "results/tutorial/diff_example.json", "java");
            System.out.println("Project diff JSON written to results/diff.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }


    }
}
