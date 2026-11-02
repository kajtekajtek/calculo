package dev.calculo.xtb;

import dev.calculo.xtb.ParsedExport.ImportProblem;
import java.util.List;

public record ImportPreview(int newOperations, int duplicates, List<ImportProblem> problems, List<String> warnings,
                            boolean canSave) {}
