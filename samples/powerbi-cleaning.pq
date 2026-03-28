let
    Source = Csv.Document(File.Contents("C:\\Users\\jesus_ctqx5w2\\Downloads\\excel madre(Hoja1).csv"),[Delimiter=";", Columns=29, Encoding=65001, QuoteStyle=QuoteStyle.None]),
    Promote = Table.PromoteHeaders(Source, [PromoteAllScalars=true]),
    RenameFirst = if List.First(Table.ColumnNames(Promote)) = "" then Table.RenameColumns(Promote, {{"", "row_id"}}) else Promote,
    TrimText = Table.TransformColumns(RenameFirst, List.Transform(Table.ColumnNames(RenameFirst), each {_, Text.Trim, type text})),

    // Normalize column names (basic manual rename)
    Renamed = Table.RenameColumns(TrimText, {
        {"TIPO CLIENTE", "tipo_cliente"},
        {"CLIENTES 2026", "cliente"},
        {"CIF", "cif"},
        {"ADMINISTRADOR", "administrador"},
        {"DNI/NIE", "dni_nie"},
        {"MINUTAS", "minutas"},
        {"F/ALTA", "f_alta"},
        {"F/BAJA", "f_baja"},
        {"F/PAGO", "f_pago"},
        {"GESTOR", "gestor"},
        {"CONT/MODELOS", "cont_modelos"},
        {"IS/IRPF", "is_irpf"},
        {"DDCC", "ddcc"},
        {"LIBROS", "libros"},
        {"CARGA DE TRABAJO", "carga_de_trabajo"},
        {"% CONTABILIDAD", "pct_contabilidad"},
        {"N AS 2015", "n_as_2015"},
        {"N AS 2016", "n_as_2016"},
        {"N AS 2017", "n_as_2017"},
        {"N AS 2018", "n_as_2018"},
        {"N AS 2019", "n_as_2019"},
        {"N AS 2020", "n_as_2020"},
        {"N AS 2021", "n_as_2021"},
        {"N AS 2022", "n_as_2022"},
        {"N AS 2023", "n_as_2023"},
        {"N AS 2024", "n_as_2024"},
        {" PROMEDIO ", "promedio"}
    }, MissingField.Ignore),

    ToNull = (v as text) as nullable text => if v = "" or v = "-" then null else v,
    CleanNulls = Table.TransformColumns(Renamed, List.Transform(Table.ColumnNames(Renamed), each {_, ToNull, type nullable text})),

    // Numeric parsing (comma decimals)
    ToNumber = (v as nullable text) as nullable number =>
        if v = null then null else
        Number.FromText(Text.Replace(Text.Replace(v, ".", ""), ",", ".")),

    NumericCols = {
        "minutas","carga_de_trabajo","pct_contabilidad","promedio",
        "n_as_2015","n_as_2016","n_as_2017","n_as_2018","n_as_2019",
        "n_as_2020","n_as_2021","n_as_2022","n_as_2023","n_as_2024"
    },
    ParsedNums = Table.TransformColumns(CleanNulls, List.Transform(NumericCols, each {_, ToNumber, type nullable number})),

    // Spanish month-year to date (first day)
    MonthMap = [
        ene="01", enero="01", feb="02", febrero="02", mar="03", marzo="03",
        abr="04", abril="04", may="05", mayo="05", jun="06", junio="06",
        jul="07", julio="07", ago="08", agosto="08", sep="09", sept="09", septiembre="09",
        oct="10", octubre="10", nov="11", noviembre="11", dic="12", diciembre="12"
    ],
    ParseMonthYear = (v as nullable text) as nullable date =>
        if v = null then null else
        let
            parts = Text.Split(Text.Lower(v), "-"),
            mon = if List.Count(parts) > 0 then Record.FieldOrDefault(MonthMap, parts{0}, null) else null,
            yy = if List.Count(parts) > 1 then parts{1} else null,
            yyyy = if yy <> null then if Text.Length(yy)=2 then "20" & yy else yy else null,
            dateText = if mon <> null and yyyy <> null then yyyy & "-" & mon & "-01" else null
        in
            if dateText = null then null else Date.FromText(dateText),

    ParsedDates = Table.TransformColumns(ParsedNums, {
        {"f_alta", ParseMonthYear, type nullable date},
        {"f_baja", ParseMonthYear, type nullable date}
    }),

    // SI/NO to boolean
    ToBool = (v as nullable text) as nullable logical =>
        if v = null then null else if Text.StartsWith(Text.Upper(v), "SI") then true else if Text.Upper(v) = "NO" then false else null,
    ParsedBools = Table.TransformColumns(ParsedDates, {
        {"cont_modelos", ToBool, type nullable logical},
        {"is_irpf", ToBool, type nullable logical},
        {"ddcc", ToBool, type nullable logical},
        {"libros", ToBool, type nullable logical}
    })

in
    ParsedBools
