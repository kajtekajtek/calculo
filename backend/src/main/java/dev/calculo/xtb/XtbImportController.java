package dev.calculo.xtb;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports/xtb")
public class XtbImportController {
    private final XtbImportService service;

    public XtbImportController(XtbImportService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ImportPreview preview(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return service.preview(name(file), in);
        }
    }

    @PostMapping("/commit")
    public ResponseEntity<ImportPreview> commit(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ImportPreview result = service.commit(name(file), in);
            return ResponseEntity.status(result.canSave() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(result);
        }
    }

    private static String name(MultipartFile file) {
        return file.getOriginalFilename() == null ? "eksport.xlsx" : file.getOriginalFilename();
    }
}
